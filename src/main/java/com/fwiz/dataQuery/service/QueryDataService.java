package com.fwiz.dataQuery.service;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;
import org.apache.velocity.exception.VelocityException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.CallableStatementCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.rowset.SqlRowSet;
import org.springframework.jdbc.support.rowset.SqlRowSetMetaData;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.fwiz.dataQuery.utils.JResponse;
import com.fwiz.dataQuery.utils.SecureUtils;
import com.fwiz.dataQuery.utils.TemplatesLoader;
import com.fwiz.dataQuery.utils.bean.FtsParam;
import com.fwiz.dataQuery.utils.bean.RptDataJson;
import com.fwiz.dataQuery.utils.bean.template.*;
import com.fwiz.utils.Configuration;

public class QueryDataService {
	private static Logger log = Logger.getLogger(QueryDataService.class);
	@Autowired
	private Configuration cg;
	protected JdbcTemplate jdbcTemplateDt;
	@Autowired
	public void setJdbcTemplateDt(JdbcTemplate jdbcTemplateDt){
		this.jdbcTemplateDt = jdbcTemplateDt;
	}
	
	public Map getData(String jpID,JSONObject params){
		Map result = new HashMap();
		JSONObject jrpt = null;
		log.info("开始查询"+jpID);
		JOutput jp = TemplatesLoader.getTemplatesLoader().getJOutput(jpID);
		if(jp==null){
			result.put("done", false);
			result.put("info", "未找到页面数据的定义信息。");
			log.equals("未找到JOutput信息，ID："+jpID);
			return result;
		}
		Properties p=new Properties(); 
		p.setProperty("file.resource.loader.class", "org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader"); 
		Velocity.init(p);  
        VelocityContext context = new VelocityContext(); 
        //先把外部请求参数也放到velocity中
        for (Map.Entry entry : params.entrySet()) {  
		   String key = (String)entry.getKey();
		   Object ov = entry.getValue();
		   if(ov!=null&&ov.getClass()==Integer.class){
			   context.put(key, (Integer)entry.getValue());
		   }else if(ov!=null&&ov.getClass()==Double.class){
			   context.put(key, (Double)entry.getValue());
		   }else{
			   try{
				   context.put(key, (String)entry.getValue());
			   }catch(Exception e){
				   log.error("解析参数值时发生错误，rptID："+jpID+",参数："+key);
				   context.put(key, "");
			   }
		   }
		}  
        log.info(jpID+"初始化模板并设置参数成功！");
		List vds = jp.getValuedDs();
		if(vds!=null){
			//vds中的ds，逐个查找，加载。
			//筛选字段的值，在参数params中查找，如果没有，则用设计文件中的值（起默认值的作用）
			log.info(jpID+"循环解析数据源,共"+vds.size()+"个数据源");
			for(int i=0;i<vds.size();i++){
				ValuedDs vd = (ValuedDs)vds.get(i);
				String dsRef = vd.getRefDtSrc();
				TemplatesLoader ltmp=TemplatesLoader.getTemplatesLoader();
				DataSrc ds = ltmp.getDataSrc(dsRef);
				if(ds.getUseType()==1){
					try{
						parseDtSrcOfRDB(jpID,vd,ds,params,context);
					}catch(Exception e){
						result.put("done", false);
						result.put("info", e.toString());
						log.equals(e.toString());
						return result;
					}
				}
			}
		}
		try{
			log.info(jpID+"开始合并模板...");
	        StringWriter sw = new StringWriter();
	        String tmp=jp.getjTemplate();
	        Velocity.evaluate(context, sw, jp.getId(), tmp);
	        log.info(jpID+"模板合并完成...");
	        String otmp = sw.toString();
	        JSONObject jtmp = JSONObject.parseObject(otmp);
	        log.info(jtmp.toJSONString());
	        result.put("done", true);
			result.put("jpData", jtmp);
		}catch(Exception ve){
			result.put("done", false);
			result.put("info", "数据源查询成功，但模板解析时发生错误。");
			log.error(ve.toString());
		}
		return result;
	}
	
	
	
	//从关系型数据库查询数据
	private void parseDtSrcOfRDB(String jpID,ValuedDs vd,DataSrc ds,JSONObject params,VelocityContext context)throws Exception{
		String dsName = vd.getName();
		log.info("解析数据源:"+dsName);
		if(ds.getSourceType()==1){
	    	excuteSql(ds,vd,params,context);
		}else if(ds.getSourceType()==2){
			excuteProcedure(ds,vd,params,context);
		}
	}
	@SuppressWarnings("unchecked")
	private void excuteProcedure(DataSrc ds,ValuedDs vd,JSONObject paramVals,VelocityContext context)throws Exception{
		final Map infos = new HashMap();
		ProcedureBean pro=ds.getProcedure();
		if(pro==null){
			log.error("未设置取数存储过程！");
			throw new Exception("数据源定了存储过程取数方式，但未找到存储过程定义！");
		}
		
		List parasIn=pro.getInParas();
		StringBuffer proStmt=new StringBuffer("{call ");
		proStmt.append(pro.getName());
		//根据输入参数定义的个数设置?
		if(parasIn!=null&&parasIn.size()>0){
			proStmt.append("(");
			for(int i=0;i<parasIn.size();i++){
				proStmt.append("?");
				if(i<parasIn.size()-1){
					proStmt.append(",");
				}
			}
		}
		//根据输出参数定义继续设置?
		List parasOut=pro.getOutParas();
		if(parasOut!=null&&parasOut.size()>0){
			if(parasIn==null||parasIn.size()==0){
				proStmt.append("(");
			}else{
				proStmt.append(",");
			}
			for(int i=0;i<parasOut.size();i++){
				proStmt.append("?");
				if(i<parasOut.size()-1){
					proStmt.append(",");
				}else{
					proStmt.append(")");
				}
			}
		}else{
			if(parasIn!=null&&parasIn.size()>0){
				proStmt.append(")");
			}
		}
		proStmt.append("}");
		final List rows = new ArrayList();
		infos.put("total", 0);
		infos.put("rows", rows);
		Map decipherCols = ds.getDecipherColsMap();
		Map parasDef = vd.getFilterFldsMap();
		jdbcTemplateDt.execute(proStmt.toString(),new CallableStatementCallback(){
			public Object doInCallableStatement(CallableStatement cs)throws SQLException, DataAccessException {
				if(parasIn!=null&&parasIn.size()>0){
					for(int i=0;i<parasIn.size();i++){
						//过程参数引用方式分直接引用固定值和引用参数两种
						ProParaIn pi=(ProParaIn)parasIn.get(i);
						if(pi!=null&&pi.getReferMode()==0){
							FilterField pf = (FilterField)parasDef.get(pi.getReferTo());
							if(pf.getDataType()==1){
								int ival=0;
								try{ival=Integer.parseInt(pi.getValue());}
								catch(Exception e){}
								cs.setInt(i+1, ival);
								log.info("参数(整型)"+pi.getReferTo()+":"+ival);
							}else if(pf.getDataType()==2){
								double dval=0;
								try{dval=Double.parseDouble(pi.getValue());}
								catch(Exception e){}
								cs.setDouble(i+1, dval);
								log.info("参数(小数)"+pi.getReferTo()+":"+dval);
							}else{
								cs.setString(i+1, pf.getValue());
								log.info("参数(字符串)"+pi.getReferTo()+":"+pi.getValue());
							}
						}else{
							if(paramVals==null){
								log.error("缺少参数值。参数："+pi.getReferTo());
							}
							//找出输入参数的定义
							FilterField pf = (FilterField)parasDef.get(pi.getReferTo());
							String val="";
							String pname = pf==null? pi.getReferTo():pf.getRefParam();
							Object oval=paramVals.get(pname);
							if(oval==null){
								val=null;
							}else{
								val = String.valueOf(oval);
							}
							if(pf!=null&&pf.getDataType()==1){
								int iVal=0;
								try{
									iVal=Integer.parseInt(val);
								}catch(Exception e){}
								cs.setInt(i+1, iVal);
								log.info("参数(整型)"+pf.getRefParam()+":"+iVal);
							}else if(pf!=null&&pf.getDataType()==2){
								double dVal=0;
								try{
									dVal=Double.parseDouble(val);
								}catch(Exception e){}
								cs.setDouble(i+1, dVal);
								log.info("参数(小数)"+pf.getRefParam()+":"+dVal);
							}else{
								cs.setString(i+1,val);
								log.info("参数(字符串)"+pname+":"+val);
							}
						}
					}
				}
				//注册输出参数
				int oStart=parasIn==null?1:parasIn.size()+1;
				if(parasOut!=null){
					for(int i=0;i<parasOut.size();i++){
						ProParaOut po=(ProParaOut)parasOut.get(i);
						if(po.getDataType()==1||po.getDataType()==2){
							cs.registerOutParameter(oStart+i, Types.NUMERIC);
						}else if(po.getDataType()==0){
							cs.registerOutParameter(oStart+i, Types.VARCHAR);
						}else if(po.getDataType()==3){
							cs.registerOutParameter(oStart+i, oracle.jdbc.OracleTypes.CURSOR);
						}
					}
				}
                cs.execute(); 
                int ti=ds.getProcedure().getTotalIndex();
            	int total = cs.getInt(oStart-1+ti);
                infos.put("total", total);
                ResultSet rs = (ResultSet)cs.getObject(oStart-1+pro.getDataSetIndex()); 
                if(rs==null){
                	return 0;
                }
                String[] flds = null;
                if(!StringUtils.isEmpty(vd.getFields())){
    				flds=vd.getFields().split(",");
    				while(rs.next()){
    		        	Map row = new HashMap();
    		        	for (int j=0;j<flds.length;j++){  
    		        		try{
    		        			String v = rs.getString(flds[j].toLowerCase());
    		        			//如果有需要解密的字段，解密该字段
    					    	if(decipherCols!=null&&decipherCols.containsKey(flds[j].toLowerCase())){
    					    		Column col = (Column)decipherCols.get(flds[j].toLowerCase());
    					    		String algorithm = col.getAlgorithm();
    					    		v = doDecipher(v,algorithm);
    					    	}
    		        			row.put(flds[j].toLowerCase(),v);
    		        		}catch(Exception e){
    		        		}
    			        }
    		        	rows.add(row);
    				}
    			}else{
	                ResultSetMetaData rsmd=rs.getMetaData();
	        		//获取元信息
	        		int colNum=rsmd.getColumnCount();
	                while (rs.next()) {
	                	Map row = new HashMap();
	                	for(int i=1;i<=colNum;i++){
	        				String colName = rsmd.getColumnLabel(i).toLowerCase();
			        		String v = rs.getString(i);
		        			//如果有需要解密的字段，解密该字段
					    	if(decipherCols!=null&&decipherCols.containsKey(colName.toLowerCase())){
					    		Column col = (Column)decipherCols.get(colName.toLowerCase());
					    		String algorithm = col.getAlgorithm();
					    		v = doDecipher(v,algorithm);
					    	}
					    	row.put(colName, v);
	        			}
	                	rows.add(row);
	                }
    			}
                infos.put("rows", rows);
                return infos;
			}
		});
		context.put(vd.getName(), infos);
		return ;
	}
	
	private void excuteSql(DataSrc ds,ValuedDs vd,JSONObject paramVals,VelocityContext context)throws Exception{
		int cc = 0;
		String sql=ds.getSql();
		if(sql==null)return ;
		Map qinfos = new HashMap();
		Map parasDef = vd.getFilterFldsMap();
		//先处理参数值替换
		String[] paras=StringUtils.substringsBetween(sql, "{", "}");
		String[] rplParas = StringUtils.substringsBetween(sql, "[", "]");
		String[] rpl2pers = StringUtils.substringsBetween(sql,"%[","]%");
		if(rpl2pers!=null&&rpl2pers.length>0){
			for(int i=0;i<rpl2pers.length;i++){
				FilterField pf = (FilterField)parasDef.get(rpl2pers[i]);
				sql = replaceParamValue(sql,pf==null?0:pf.getDataType(),rpl2pers[i],paramVals,2);
			}
		}
		String[] rplFpers=StringUtils.substringsBetween(sql,"%[","]");
		if(rplFpers!=null&&rplFpers.length>0){
			for(int i=0;i<rplFpers.length;i++){
				FilterField pf = (FilterField)parasDef.get(rpl2pers[i]);
				sql = replaceParamValue(sql,pf==null?0:pf.getDataType(),rplFpers[i],paramVals,0);
			}
		}
		String[] rplTpers=StringUtils.substringsBetween(sql,"[","]%");
		if(rplTpers!=null&&rplTpers.length>0){
			for(int i=0;i<rplTpers.length;i++){
				FilterField pf = (FilterField)parasDef.get(rpl2pers[i]);
				sql = replaceParamValue(sql,pf==null?0:pf.getDataType(),rplTpers[i],paramVals,1);
			}
		}
		if(rplParas!=null&&rplParas.length>0){
			for(int i=0;i<rplParas.length;i++){
				FilterField pf = (FilterField)parasDef.get(rpl2pers[i]);
				sql = replaceParamValue(sql,pf==null?0:pf.getDataType(),rplParas[i],paramVals,9);
			}
		}
		//如果有参数引用――{abc..}，替换成?，并提取其中的参数
		//2009-04-28为适应like中的%%
		String[] has2pers=StringUtils.substringsBetween(sql,"%{","}%");
		sql=sql.replaceAll("%\\{\\w*\\}%","?");
		String[] hasFpers=StringUtils.substringsBetween(sql,"%{","}");
		sql=sql.replaceAll("%\\{\\w*\\}","?");
		//String[] hasTpers=StringUtils.substringsBetween(sql,"{","}%");
		List lstTpers = new ArrayList();
		String tmpSql = sql;
		while(true){
			int end= tmpSql.indexOf("}%");
			if(end<0){
				break;
			}
			String preSql = tmpSql.substring(0,end);
			int start = preSql.lastIndexOf("{");
			String p = preSql.substring(start+1,end);
			tmpSql = tmpSql.substring(end+1);
			lstTpers.add(p);
		}
		String[] hasTpers =lstTpers.size()==0?null:new String[lstTpers.size()]; 
		for(int i=0;i<lstTpers.size();i++){
			hasTpers[i]=(String)lstTpers.get(i);
		}
		sql=sql.replaceAll("\\{\\w*\\}%","?");
		Map paraSearchModes=new HashMap();
		if(has2pers!=null){
			for(int i=0;i<has2pers.length;i++){
				paraSearchModes.put(has2pers[i], "2");
			}
		}
		if(hasFpers!=null){
			for(int i=0;i<hasFpers.length;i++){
				paraSearchModes.put(hasFpers[i], "0");
			}
		}
		if(hasTpers!=null){
			for(int i=0;i<hasTpers.length;i++){
				paraSearchModes.put(hasTpers[i], "1");
			}
		}
		sql=sql.replaceAll("\\{\\w*\\}","?");
		StringBuffer qSql = new StringBuffer("SELECT COUNT(*) AS RCOUNT FROM(");
        qSql.append(sql).append(")");
      //如果没有参数引用
  		if(paras==null||paras.length==0){
  			cc = jdbcTemplateDt.queryForObject(qSql.toString(),Integer.class);
  		}else{
  			Object[] params = parseSqlParameter(parasDef,paramVals,paras,paraSearchModes);
  			cc = jdbcTemplateDt.queryForObject(qSql.toString(),params,Integer.class);
  		}
	    qinfos.put("total", cc);
		//分页时，加工分页sql
		if(vd.isPaging()){
			String fromFld = vd.getStartParam();
			String sizeFld = vd.getSizeParam();
			int from = 0,size=0;
			try{
				from = paramVals.getInteger(fromFld);
			}catch(Exception e){}
			try{
				size = paramVals.getInteger(sizeFld);
			}catch(Exception e){}
			if(size==0){
				size=10;
			}
			qSql = new StringBuffer("SELECT * FROM (SELECT A.*, rownum r FROM (");
	        qSql.append(sql);
	        qSql.append(") A WHERE rownum<=");
	        qSql.append((from+size));
	        qSql.append(") B WHERE r>");
	        qSql.append(from);
	        sql = qSql.toString();
		}else{
			qSql = new StringBuffer(sql);
		}
		List lst = null;
		//如果没有参数引用
		if(paras==null||paras.length==0){
			lst = jdbcTemplateDt.queryForList(qSql.toString());
		}else{
			Object[] params = parseSqlParameter(parasDef,paramVals,paras,paraSearchModes);
			lst = jdbcTemplateDt.queryForList(qSql.toString(),params);
		}
		Map decipherCols = ds.getDecipherColsMap();
		if(lst!=null&&lst.size()>0){
			List rows = new ArrayList();
			String[] flds = null;
			if(!StringUtils.isEmpty(vd.getFields())){
				flds=vd.getFields().split(",");
				for(int i=0;i<lst.size();i++){
					Map row = (Map)lst.get(i);
		        	Map nrow = new HashMap();
		        	for (int j=0;j<flds.length;j++){  
		        		if(row.containsKey(flds[j].toLowerCase())){
		        			String v = row.get(flds[j].toLowerCase())==null?"":row.get(flds[j].toLowerCase()).toString();
		        			//如果有需要解密的字段，解密该字段
					    	if(decipherCols!=null&&decipherCols.containsKey(flds[j].toLowerCase())){
					    		Column col = (Column)decipherCols.get(flds[j].toLowerCase());
					    		String algorithm = col.getAlgorithm();
					    		v = doDecipher(v,algorithm);
					    	}
		        			nrow.put(flds[j].toLowerCase(),v); 
		        		}
			        }
		        	rows.add(nrow);
				}
			}else{
		        for(int i=0;i<lst.size();i++){
		        	Map row = (Map)lst.get(i);
		        	Map nrow = new HashMap();
		        	for (Iterator it = row.keySet().iterator(); it.hasNext();) {  
		        		String key = (String)it.next();
		        		String v = row.get(key)==null?"":row.get(key).toString();
	        			//如果有需要解密的字段，解密该字段
				    	if(decipherCols!=null&&decipherCols.containsKey(key.toLowerCase())){
				    		Column col = (Column)decipherCols.get(key.toLowerCase());
				    		String algorithm = col.getAlgorithm();
				    		v = doDecipher(v,algorithm);
				    	}
		        		nrow.put(key.toLowerCase(), v); 
			        }
		        	rows.add(nrow);
		        }
			}
			qinfos.put("rows", rows);
		    context.put(vd.getName(), qinfos);
		}
		return;
	}
	private String replaceParamValue(String sql,int dataType,String pName,JSONObject paraValues,int rmode){
		String rvalue = (String)paraValues.get(pName);
		//按参数类型转变值
		if(dataType==0){
			if(rmode==9){//如果不是like，('%%')的形式，需转化成每个逗号之间都插入单引号
				rvalue = rvalue.replaceAll(",", "','");
			}
		}
		//值的替换，有like操作的，一律当字符串处理，加''，非like操作，要根据数据类型确定是否添加''
		if(rmode==2){//%在两头
			sql=sql.replace("%["+pName+"]%","'%"+rvalue+"%'");
		}else if(rmode==0){//%在前
			sql=sql.replace("%["+pName+"]","'%"+rvalue+"'");
		}else if(rmode==1){//%在后
			sql=sql.replace("["+pName+"]%","'"+rvalue+"%'");
		}else{//无%
			sql=sql.replace("["+pName+"]","'"+rvalue+"'");
		}
		return sql;
	}
	private Object[] parseSqlParameter(Map parasDef,JSONObject paraValues,String[] paras,Map fuzzySearchPara)throws Exception{
		//如果没有引用参数，可以直接返回
		if(paras==null||paras.length==0)return null;
		Object[] pvs = new Object[paras.length];
		for(int i=0;i<paras.length;i++){
			FilterField pf = (FilterField)parasDef.get(paras[i]);
			String val="";
			String pname = pf==null? paras[i]:pf.getRefParam();
			Object oval=paraValues.get(pname);
			if(oval==null){
				val=null;
			}else{
				val = String.valueOf(oval);
			}
			if(pf!=null&&pf.getDataType()==1){
				int iVal=0;
				try{
					iVal=Integer.parseInt(val);
				}catch(Exception e){}
				pvs[i]=iVal;
			}else if(pf!=null&&pf.getDataType()==2){
				double dVal=0;
				try{
					dVal=Double.parseDouble(val);
				}catch(Exception e){}
				pvs[i]=dVal;
			}else{
				if(fuzzySearchPara.containsKey(paras[i])){
					if("2".equals(fuzzySearchPara.get(paras[i]))){
						val="%"+val+"%";
					}else if("0".equals(fuzzySearchPara.get(paras[i]))){
						val="%"+val;
					}else if("1".equals(fuzzySearchPara.get(paras[i]))){
						val=val+"%";
					}
				}
				pvs[i]=val;
			}
			log.info("参数"+pname+":"+val);
		}
		return pvs;
	 }
//	private String replaceBlank(String str) {
//		String dest = "";
//		if (str!=null) {
//			Pattern p = Pattern.compile("\\t|\r|\n");
//			Matcher m = p.matcher(str);
//			dest = m.replaceAll("");
//		}
//		return dest;
//	}

	private String doDecipher(String rawValue,String algorithm){
		String newVal = SecureUtils.decipher(rawValue,algorithm);
		return newVal;
	}
	//2020-05 获取全文检索可关联的资源
	public List getFtsResources(String idx) throws Exception{
		List objs = new ArrayList();
		StringBuffer sql =new StringBuffer("select * from fts_resource where idx =? order by showorder");
		List rows = jdbcTemplateDt.queryForList(sql.toString(),new Object[]{idx});
	    for(int i=0;i<rows.size();i++) {
	    	Map row = (Map)rows.get(i);
	    	Map o = new HashMap();
	    	Iterator it = row.entrySet().iterator();
            while(it.hasNext()) {
                Entry<String, Object> entry = (Entry)it.next();
                o.put(((String)entry.getKey()).toLowerCase(), row.get(entry.getKey()));
            }
	    	objs.add(o);
	    }
		return objs;
	}

	public Map oraFtsByKeyWord(String idx, FtsParam params) throws Exception{
		Map result = new HashMap();
		long total =0; 
	    List objs = null;
		JSONObject jparams = params==null?null:params.parseFtsParams();
		String searchKey = jparams.containsKey("searchKey")?jparams.getString("searchKey"):null;
		if(StringUtils.isEmpty(searchKey)){
			result.put("total", total);
		    result.put("matches", objs);
	        return result;
		}
		int searchSize = jparams.containsKey("searchSize")?jparams.getIntValue("searchSize"):5;
		//sql查找
		Map midx = cg.getOraFtsIdxMap();
		if(midx==null){
			throw new Exception("未配置可用的全文检索索引！");
		}
		if(!midx.containsKey(idx)){
			throw new Exception("未找到指定索引"+idx+"的配置信息！");
		}
		Map oi=(Map)midx.get(idx);
		String sql = (String)oi.get("qsql");
		if(sql==null||"".equals(sql)){
			throw new Exception("未找到指定索引"+idx+"的配置搜索语句配置信息！"); 
		}
		List rows = jdbcTemplateDt.queryForList(sql.toString(),new Object[]{searchKey,searchSize});
	    if(rows!=null){
	    	total = rows.size();
	    	objs = new ArrayList();
		    for(int i=0;i<rows.size();i++) {
		    	Map row = (Map)rows.get(i);
		    	Map o = new HashMap();
		    	Iterator it = row.entrySet().iterator();
	            while(it.hasNext()) {
	                Entry<String, Object> entry = (Entry)it.next();
	                o.put(((String)entry.getKey()).toLowerCase(), row.get(entry.getKey()));
	            }
		    	objs.add(o);
		    }
	    }
	    result.put("total", total);
	    result.put("matches", objs);
        return result;
	}
}
