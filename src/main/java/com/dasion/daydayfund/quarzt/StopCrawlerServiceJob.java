package com.dasion.daydayfund.quarzt;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.dasion.daydayfund.constant.RedisConstant;
import com.dasion.daydayfund.fund.FundBean;
import com.dasion.daydayfund.mail.MailTemplate;
import com.dasion.daydayfund.tool.JedisTool;
import com.google.gson.Gson;

import redis.clients.jedis.Jedis;
@Component
public class StopCrawlerServiceJob{
	private static final Logger logger = LoggerFactory.getLogger(StopCrawlerServiceJob.class);
	@Autowired
	private JedisTool jedisTool;
	@Autowired
	MailTemplate mailTemplate;

	public void execute() throws JobExecutionException {
		DateTimeFormatter format = DateTimeFormat.forPattern("yyyy-MM-dd");
		String nowDate = DateTime.now().toString(format);
		try (Jedis jedis = jedisTool.getJedisTool().getResource()) {
			logger.info("守护任务: " + jedis.get(RedisConstant.SEMAPHORE_KEY));
			if (RedisConstant.SEMAPHORE_KEY_RUN.equals(jedis.get(RedisConstant.SEMAPHORE_KEY))) {
				if (jedis.exists(RedisConstant.SOURCE_DATA_QUEUE + nowDate) == false 
						&& jedis.exists(RedisConstant.FINAL_DATA_QUEUE + nowDate) == true) {
					//设置为stop是为了让线程池里边的线程结束生命周期，释放资源
					jedis.set(RedisConstant.SEMAPHORE_KEY, RedisConstant.SEMAPHORE_KEY_STOP);
					try {
						//这里睡眠30秒是为了让线程池里边的线程有足够的时间侦测到SEMAPHORE_KEY为stop并结束自身生命周期释放资源
						TimeUnit.MILLISECONDS.sleep(30000);
						//wait为一个长周期的状态，直到下一次定时器任务启动时才会被更改为run
						jedis.set(RedisConstant.SEMAPHORE_KEY, RedisConstant.SEMAPHORE_KEY_WAIT);
						List<FundBean> gongYinFunds = new ArrayList<>(1000);

						Gson gson = new Gson();
						String finalDataQueue = RedisConstant.FINAL_DATA_QUEUE + nowDate;
						int len = jedis.llen(finalDataQueue).intValue();
						List<String> jsonStr = jedis.lrange(finalDataQueue, 0, len - 1);
						for (String json : jsonStr) {
							gongYinFunds.add(gson.fromJson(json, FundBean.class));
						}

						mailTemplate.megerDataAndTemplate(gongYinFunds, null);
					} catch (Exception e) {
						logger.error("监控任务发生异常： " , e);
					}
				}

				//做即时统计
				BigDecimal count = new BigDecimal((jedis.get( RedisConstant.COUNT_BASE_INFO) == null) ? "0" : jedis.get( RedisConstant.COUNT_BASE_INFO ) );
				BigDecimal detailCount = new BigDecimal((jedis.get( RedisConstant.COUNT_DETAIL_INFO ) == null) ? "0" : jedis.get( RedisConstant.COUNT_DETAIL_INFO ) );
				BigDecimal incrCount = new BigDecimal((jedis.get( RedisConstant.COUNT_INC_INFO ) == null) ? "0" : jedis.get( RedisConstant.COUNT_INC_INFO ) );
				BigDecimal finalCount = new BigDecimal(jedis.llen( RedisConstant.FINAL_DATA_QUEUE + nowDate ));
				DecimalFormat df = new DecimalFormat("#.00");
				if(detailCount.intValue() != 0 && incrCount.intValue() == 0){
					logger.info( "当前进度：" + df.format(detailCount.divide(count,2, BigDecimal.ROUND_HALF_UP).multiply( new BigDecimal("0.2")).multiply( new BigDecimal("100"))) + " %");
				}

				if(finalCount.intValue() > 1){
					BigDecimal p = (finalCount.divide( count, 2, BigDecimal.ROUND_HALF_UP ).add( new BigDecimal( "0.15" ) )).multiply( new BigDecimal( "100" ) );
					if(p.compareTo( new BigDecimal( "70" ) ) >0){
						logger.info( "当前进度：" + df.format(finalCount.divide( count, 2, BigDecimal.ROUND_HALF_UP ).multiply( new BigDecimal( "100" ))) + " %");
					}else{
						logger.info( "当前进度：" + df.format(p)+ " %");
					}

				}
			}
		}
	}

}
