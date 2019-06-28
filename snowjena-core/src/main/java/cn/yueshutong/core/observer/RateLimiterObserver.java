package cn.yueshutong.core.observer;

import cn.yueshutong.commoon.entity.LimiterRule;
import cn.yueshutong.commoon.enums.LimiterModel;
import cn.yueshutong.core.config.RateLimiterConfig;
import cn.yueshutong.core.exception.SnowJeanException;
import cn.yueshutong.core.limiter.RateLimiter;
import cn.yueshutong.monitor.entity.MonitorBean;
import com.alibaba.fastjson.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 观察者模式（一对多）
 */
public class RateLimiterObserver {
    private static Map<String, RateLimiter> map = new ConcurrentHashMap<>();
    private static Logger logger = LoggerFactory.getLogger(RateLimiterObserver.class);

    public static void registered(RateLimiter rule, RateLimiterConfig config) {
        if (map.containsKey(rule.getId())) {
            throw new SnowJeanException("Repeat registration for current limiting rules:" + rule.getId());
        }
        map.put(rule.getId(), rule);
        update(rule, config);
        monitor(rule, config);
    }

    /**
     * 发送心跳并更新限流规则
     */
    private static void update(RateLimiter limiter, RateLimiterConfig config) {
        config.getScheduledThreadExecutor().scheduleWithFixedDelay(() -> {
            String rules = config.getTicketServer().connect(RateLimiterConfig.heart, JSON.toJSONString(limiter.getRule()));
            if (rules == null) {
                logger.debug("update limiter fail, automatically switch to local current limit");
                LimiterRule rule = limiter.getRule();
                rule.setLimiterModel(LimiterModel.POINT);
                limiter.init(rule);
                return;
            }
            LimiterRule limiterRule = JSON.parseObject(rules, LimiterRule.class);
            if (limiterRule.getVersion() > limiter.getRule().getVersion()) {
                logger.warn("update rule version: {} -> {}", limiter.getRule().getVersion(), limiterRule.getVersion());
                map.get(limiter.getId()).init(limiterRule);
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    /**
     * 监控数据上报
     */
    private static void monitor(RateLimiter limiter, RateLimiterConfig config) {
        config.getScheduledThreadExecutor().scheduleWithFixedDelay(() -> {
            if (limiter.getRule().getMonitor() == 0) {
                //监控功能已关闭
                return;
            }
            List<MonitorBean> monitorBeans = limiter.getMonitorService().getAndDelete();
            if (monitorBeans.size() < 1) {
                return;
            }
            String result = config.getTicketServer().connect(RateLimiterConfig.monitor, JSON.toJSONString(monitorBeans));
            if (result == null) {
                logger.debug("monitor data update fail");
            }
        }, 0, 3, TimeUnit.SECONDS);
    }

}
