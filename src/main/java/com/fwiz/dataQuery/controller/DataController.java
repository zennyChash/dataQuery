package com.fwiz.dataQuery.controller;

import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.alibaba.fastjson.JSONObject;
import com.fwiz.dataQuery.service.QueryDataService;
import com.fwiz.dataQuery.utils.JResponse;
import com.fwiz.dataQuery.utils.TemplatesLoader;
import com.fwiz.dataQuery.utils.bean.FtsParam;
import com.fwiz.dataQuery.utils.bean.RptDataJson;
import com.fwiz.dataQuery.utils.bean.template.JOutput;
import com.fwiz.utils.*;

@Controller
public class DataController {
	private static Logger log = Logger.getLogger(DataController.class);
	@Autowired
	private QueryDataService qdService;
	@Autowired
	private Configuration cg;
	
	@RequestMapping(value="/queryData",method = RequestMethod.POST)
	@ResponseBody
	public JResponse queryData(@RequestBody RptDataJson params){
		JResponse jr = null;
		if(params!=null){
			String rptID = params.getRptID();
			JSONObject qParams = params.parseJRptParams();
			Map data = null;
			try{
				data = qdService.getData(rptID,qParams);
			}catch(Exception e){
				log.info("queryData异常。rptID："+rptID+"异常:"+e.toString());
				jr = new JResponse("9","查询数据时发生异常，未能查找到数据。",null);
				return jr;
			}
			if(data!=null&&data.containsKey("done")){
				boolean done = (Boolean)data.get("done");
				if(done){
					JSONObject jdata = (JSONObject)data.get("jpData");
					jr = new JResponse("0","",jdata);
				}else{
					String info = (String)data.get("info");
					jr = new JResponse("9",info,null);
				}
			}else{
				jr = new JResponse("9","获取页面数据失败！",null);
			}
			log.info(rptID+"的输出:"+jr.toString());
		}else{
			jr = new JResponse("9","获取报表数据失败，没有获得正确的请求参数！",null);
		}
		return jr;
	}
	//oracle全文检索，返回可用的索引类型
	@RequestMapping("/getOraIdx")
	@ResponseBody
	public JResponse getOraIdxes(HttpServletRequest request){
		JResponse jr = new JResponse();
		List idx = cg.getOraFtsIdx();
		jr.setRetCode("0");
		jr.setRetMsg("");
		jr.setRetData(idx);
		return jr;
	}
	
	//oracle全文检索，返回可用的索引类型
	@RequestMapping("/getFtsResources")
	@ResponseBody
	public JResponse getFtsResources(@RequestParam("idx") String idx){
		JResponse jr = new JResponse();
		List rs = null;
		try{
			rs = qdService.getFtsResources(idx);
			jr.setRetCode("0");
			jr.setRetMsg("");
			jr.setRetData(rs);
		}catch(Exception e){
			jr.setRetCode("9");
			jr.setRetMsg(e.toString());
			jr.setRetData(null);
		}
		
		return jr;
	}
	
	@RequestMapping(value="/ftsWords",method = RequestMethod.POST)
	@ResponseBody
	public JResponse ftsWords(@RequestBody FtsParam params){
		JResponse jr = null;
		if(params!=null){
			String idx = params.getIdx();
			if(StringUtils.isEmpty(idx)){
				return new JResponse("9","未指定要查询的索引！",null);
			}else{
				try{
					Map data = qdService.oraFtsByKeyWord(idx,params);
					jr = new JResponse("0","",data);
				}catch(Exception e){
					jr = new JResponse("9",e.getMessage(),null);
				}
			}
		}else{
			jr = new JResponse("9","未提供全文检索需要的参数！",null);
		}
		return jr;
	}
	@RequestMapping(value="/refreshDataSrcs")
	@ResponseBody
	public JResponse refreshDataSrcs(){
		JResponse jr = new JResponse();
		TemplatesLoader tloader = TemplatesLoader.getTemplatesLoader();
		tloader.refreshDataSrcs();
		jr.setRetCode("0");
		jr.setRetMsg("");
		return jr;
	}
	@RequestMapping(value="/refreshJSONOutputs")
	@ResponseBody
	public JResponse refreshJSONOutputs(){
		JResponse jr = new JResponse();
		TemplatesLoader tloader = TemplatesLoader.getTemplatesLoader();
		tloader.refreshJSONOutputs();
		jr.setRetCode("0");
		jr.setRetMsg("");
		return jr;
	}
}
