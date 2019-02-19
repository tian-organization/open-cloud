package com.github.lyd.msg.producer.listener;

import com.alibaba.fastjson.JSONObject;
import com.github.lyd.common.http.OpenRestTemplate;
import com.github.lyd.msg.client.constants.MessageConstants;
import com.github.lyd.msg.client.dto.HttpNotification;
import com.github.lyd.msg.client.entity.MessageHttpNotifyLogs;
import com.github.lyd.msg.producer.service.HttpNotifyLogsService;
import com.github.lyd.msg.producer.service.MessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 消息接收者
 *
 * @author liuyadu
 */
@Configuration
public class MessageHandler {
    private Logger logger = LoggerFactory.getLogger(getClass());
    /**
     * 返回结果
     */
    private static final String SUCCESS = "success";
    @Autowired
    private OpenRestTemplate restTemplate;
    @Autowired
    private MessageSender messageSender;
    @Autowired
    private HttpNotifyLogsService httpNotifyLogsService;
    /**
     * 首次是即时推送，重试通知时间间隔为 5s、10s、2min、5min、10min、30min、1h、2h、6h、15h，直到你正确回复状态 200 并且返回 success 或者超过最大重发次数 或者超过最大重发次数
     */
    public final static List<Integer> DELAY_TIMES = Arrays.asList(new Integer[]{
            1* 1000,
            3* 1000,
            5* 1000,
            6* 1000,
            7* 1000,
            10* 1000,
            12* 1000
    });

    @RabbitListener(queues = MessageConstants.QUEUE_MSG)
    public void handleMessage(Message message) {
        try {
            String type = message.getMessageProperties().getType();
            String msgId = message.getMessageProperties().getMessageId();
            String receivedMsg = new String(message.getBody(), "UTF-8");
            logger.info("===================");
            logger.info("onMessage: type = {}, body = {}", type, receivedMsg);
            // 处理 http通知消息
            if (MessageConstants.HTTP_NOTIFY_TYPE.equals(type)) {
                HttpNotification notification = JSONObject.parseObject(receivedMsg, HttpNotification.class);
                httpHandler(msgId, message, notification);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    /**
     * 处理http消息通知
     */
    protected void httpHandler(String msgId, Message message, HttpNotification notification) throws Exception {
        Map<String, Object> headers = message.getMessageProperties().getHeaders();
        // 默认延迟时间
        String originalExpiration = "";
        if (headers != null) {
            List<Map<String, Object>> xDeath = (List<Map<String, Object>>) headers.get("x-death");
            if (xDeath != null && !xDeath.isEmpty()) {
                Map<String, Object> entrys = xDeath.get(0);
                originalExpiration = entrys.get("original-expiration").toString();
            }
        }
        String httpResult = "";
        try {
            httpResult = restTemplate.postForObject(notification.getUrl(),null,String.class, notification.getData());
        } catch (Exception e) {
            logger.error("http error:", e);
        }
        MessageHttpNotifyLogs log = new MessageHttpNotifyLogs();
        log.setMsgId(msgId);
        log.setUrl(notification.getUrl());
        log.setType(notification.getType());
        log.setData(JSONObject.toJSONString(notification.getData()));
        log.setRetryNums(0);
        log.setResult(new Short("0"));
        log.setDelay(0L);
        log.setTotalNums(1);
        // 添加日志
        addLog(log);
        log = httpNotifyLogsService.getLog(msgId);
        log.setTotalNums(log.getTotalNums() + 1);
        // 通知返回结果为(success,不区分大小写). 则视为通知成功
        if (!SUCCESS.equalsIgnoreCase(httpResult)) {
            logger.info("result fail do retry:");
            Integer next = retry(msgId, notification, originalExpiration);
            // 更新日志
            if (next != null) {
                log.setRetryNums(log.getRetryNums().intValue() + 1);
                log.setDelay(Long.parseLong(String.valueOf(next)));
                modifyLog(log);
            }
        } else {
            // 更新结果
            log.setResult(new Short("1"));
            modifyLog(log);
        }
    }

    /**
     * 获取下一个通知时间
     * 返回Null表示最终通知失败
     *
     * @param originalExpiration
     * @return
     */
    protected Integer getNext(String originalExpiration) {
        if (StringUtils.isEmpty(originalExpiration)) {
            return DELAY_TIMES.get(0);
        }
        int index = DELAY_TIMES.indexOf(Integer.parseInt(originalExpiration));
        if (index >= DELAY_TIMES.size() - 1) {
            return null;
        }
        int nextInterval = DELAY_TIMES.get(index + 1);
        return nextInterval;
    }

    /**
     * 通知失败重试
     *
     * @param originalExpiration
     */
    protected Integer retry(String msgId, HttpNotification notification, String originalExpiration) throws Exception {
        Integer next = getNext(originalExpiration);
        if (next != null) {
            // 下次延迟时间
            logger.info("current ={} next ={}", originalExpiration, next);
            messageSender.send(msgId, JSONObject.toJSONString(notification), MessageConstants.HTTP_NOTIFY_TYPE, next);
            return next;
        } else {
            // 最后一次
            logger.info("finish = {}", originalExpiration);
            return null;
        }
    }

    /**
     * 添加日志
     *
     * @param log
     */
    protected void addLog(MessageHttpNotifyLogs log) {
        if (log == null || StringUtils.isEmpty(log.getMsgId())) {
            return;
        }
        MessageHttpNotifyLogs saved = httpNotifyLogsService.getLog(log.getMsgId());
        if (saved == null) {
            log.setCreateTime(new Date());
            log.setUpdateTime(log.getCreateTime());
            httpNotifyLogsService.addLog(log);
        }
    }

    /**
     * 更新日志
     *
     * @param log
     */
    protected void modifyLog(MessageHttpNotifyLogs log) {
        if (log == null || StringUtils.isEmpty(log.getMsgId())) {
            return;
        }
        log.setUpdateTime(new Date());
        httpNotifyLogsService.modifyLog(log);
    }
}