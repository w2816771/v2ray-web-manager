package com.jhl.cache;

import com.alibaba.fastjson.JSON;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Maps;
import com.jhl.constant.ManagerConstant;
import com.jhl.utils.SynchronizedInternerUtils;
import com.jhl.v2ray.service.V2rayService;
import com.ljh.common.model.ProxyAccount;
import com.ljh.common.model.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 运行时保持
 */
@Slf4j
@Component
public class ProxyAccountCache {

    @Autowired
    ManagerConstant managerConstant;
    @Autowired
    RestTemplate restTemplate;
    @Autowired
    V2rayService v2rayService;

    private static final Short BEGIN_BLOCK = 3;
    Cache<String, ProxyAccount> PA_MAP = CacheBuilder.newBuilder().maximumSize(1000).expireAfterWrite(1, TimeUnit.HOURS).build();
    //  CacheBuilder<String, Object> block_account =    CacheBuilder<String, Object>.CacheBuilder
    //accountno ,int
    Cache<String, AtomicInteger> REQUEST_ERROR_COUNT = CacheBuilder.newBuilder().maximumSize(1000).expireAfterWrite(2, TimeUnit.MINUTES).build();


    public void addOrUpdate(ProxyAccount proxyAccount) {
        if (null == proxyAccount || proxyAccount.getAccountId() == null
        ) throw new NullPointerException("ProxyAccount is null");

        PA_MAP.put(getKey(proxyAccount.getAccountNo(), proxyAccount.getHost()), proxyAccount);
    }

    public ProxyAccount get(String accountNo,String host) {
        ProxyAccount proxyAccount = PA_MAP.getIfPresent(getKey(accountNo, host));

        AtomicInteger reqCountObj = REQUEST_ERROR_COUNT.getIfPresent(accountNo);
        int reqCount = reqCountObj == null ? 0 : reqCountObj.get();
        if (proxyAccount == null && reqCount < BEGIN_BLOCK) {

            synchronized (SynchronizedInternerUtils.getInterner().intern(getKey(accountNo, host+":getRemotePAccount"))) {

                proxyAccount = PA_MAP.getIfPresent(getKey(accountNo, host));
                if (proxyAccount != null) return proxyAccount;
                //远程请求，获取信息
                proxyAccount = getRemotePAccount(accountNo,host);

                //如果获取不到账号，增加错误次数
                if (proxyAccount == null) {
                    AtomicInteger counter = REQUEST_ERROR_COUNT.getIfPresent(accountNo);

                    if (counter != null) {
                        counter.addAndGet(1);
                    } else {
                        REQUEST_ERROR_COUNT.put(accountNo, new AtomicInteger(1));
                    }


                } else {
                    //确保存在账号
                    addOrUpdate(proxyAccount);
                    try {
                        v2rayService.addProxyAccount(proxyAccount.getV2rayHost(), proxyAccount.getV2rayManagerPort(), proxyAccount);
                    } catch (Exception e) {
                        log.warn("增加失败:{}", e.getLocalizedMessage());
                    }


                }
            }
        }
        if (reqCount >= BEGIN_BLOCK) log.info("阻止远程请求:{}", accountNo);


        return proxyAccount;
    }


    private ProxyAccount getRemotePAccount(String accountNo,String host) {
        log.info("getRemotePAccount:{}", getKey(accountNo, host));
        HashMap<String, Object> kvMap = Maps.newHashMap();
        kvMap.put("accountNo", accountNo);
        kvMap.put("domain", host);
        ResponseEntity<Result> entity = restTemplate.getForEntity(managerConstant.getGetProxyAccountUrl(),
                Result.class, kvMap);
        if (!entity.getStatusCode().is2xxSuccessful()) {
            log.error("获取pAccount 错误:{}", entity);
            return null;
        }
        Result result = entity.getBody();
        if (result.getCode() != 200) {
            log.warn("getRemotePAccount  error:{}", JSON.toJSONString(result));
            return null;
        }
        return JSON.parseObject(JSON.toJSONString(result.getObj()), ProxyAccount.class);
    }

    public void rmProxyAccountCache(String accountNo,String host) {
        PA_MAP.invalidate(getKey(accountNo, host));
    }

    private String getKey(String accountNo, String host) {
        return accountNo + ":"+host;
    }

}
