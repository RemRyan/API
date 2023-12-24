package com.ljh.apigateway;

import com.ljh.apiclientsdk.utils.SignUtils;
import com.ljh.apicommon.common.ErrorCode;
import com.ljh.apicommon.exception.BusinessException;
import com.ljh.apicommon.model.entity.InterfaceInfo;
import com.ljh.apicommon.model.entity.User;
import com.ljh.apicommon.model.enums.InterfaceInfoStatusEnum;
import com.ljh.apicommon.service.InnerInterfaceInfoService;
import com.ljh.apicommon.service.InnerUserInterfaceInfoService;
import com.ljh.apicommon.service.InnerUserService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.dubbo.config.annotation.DubboReference;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 全局过滤
 */
@Slf4j
@Component
public class CustomGlobalFilter implements GlobalFilter, Ordered {

    @DubboReference
    private InnerUserService innerUserService;

    @DubboReference
    private InnerInterfaceInfoService innerInterfaceInfoService;

    @DubboReference
    private InnerUserInterfaceInfoService innerUserInterfaceInfoService;

    private static final List<String> IP_WHITE_LIST = Collections.singletonList("127.0.0.1");

    private static final Long FIVE_MINUTES = 60 * 5L;


    private static final String INTERFACE_HOST = "http://localhost:8123";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1. 请求日志
        ServerHttpRequest request = exchange.getRequest();
        String path = INTERFACE_HOST + request.getPath().value();
        String method = request.getMethod().toString();
        log.info("请求唯一标识：" + request.getId());
        log.info("请求路径：" + path);
        log.info("请求方法：" + method);
        log.info("请求参数：" + request.getQueryParams());
        String sourceAddress = request.getLocalAddress().getHostString();
        log.info("请求来源地址：" + sourceAddress);
        log.info("请求来源地址：" + request.getRemoteAddress());
        Mono<Void> result = verifyParameters(exchange, chain);
        return result;
    }

    /**
     * 处理响应
     *
     * @param exchange
     * @param chain
     * @return
     */
    public Mono<Void> handleResponse(ServerWebExchange exchange, GatewayFilterChain chain, long interfaceInfoId, long userId) {
        try {
            ServerHttpResponse originalResponse = exchange.getResponse();
            // 缓存数据的工厂
            DataBufferFactory bufferFactory = originalResponse.bufferFactory();
            // 拿到响应码
            HttpStatus statusCode = originalResponse.getStatusCode();
            if (statusCode == HttpStatus.OK) {
                // 装饰，增强能力
                ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {
                    // 等调用完转发的接口后才会执行
                    @Override
                    public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                        log.info("body instanceof Flux: {}", (body instanceof Flux));
                        if (body instanceof Flux) {
                            Flux<? extends DataBuffer> fluxBody = Flux.from(body);
                            // 往返回值里写数据
                            // 拼接字符串
                            return super.writeWith(
                                    fluxBody.map(dataBuffer -> {
                                        // 7. 调用成功，接口调用次数 + 1 invokeCount
                                        try {
                                            innerUserInterfaceInfoService.invokeCount(interfaceInfoId, userId);
                                        } catch (Exception e) {
                                            log.error("invokeCount error", e);
                                        }
                                        byte[] content = new byte[dataBuffer.readableByteCount()];
                                        dataBuffer.read(content);
                                        DataBufferUtils.release(dataBuffer);//释放掉内存
                                        // 构建日志
                                        StringBuilder sb2 = new StringBuilder(200);
                                        List<Object> rspArgs = new ArrayList<>();
                                        rspArgs.add(originalResponse.getStatusCode());
                                        String data = new String(content, StandardCharsets.UTF_8); //data
                                        sb2.append(data);
                                        // 打印日志
                                        log.info("响应结果：" + data);
                                        return bufferFactory.wrap(content);
                                    }));
                        } else {
                            // 8. 调用失败，返回一个规范的错误码
                            log.error("<--- {} 响应code异常", getStatusCode());
                        }
                        return super.writeWith(body);
                    }
                };
                // 设置 response 对象为装饰过的
                return chain.filter(exchange.mutate().response(decoratedResponse).build());
            }
            return chain.filter(exchange); // 降级处理返回数据
        } catch (Exception e) {
            log.error("网关处理响应异常" + e);
            return chain.filter(exchange);
        }
    }

    /**
     * 校验参数
     *
     * @param exchange
     * @param chain
     * @return
     */
    private Mono<Void> verifyParameters(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        // 获取访问的主机 ip 地址
        String sourceAddress = Objects.requireNonNull(request.getLocalAddress()).getHostString();
        ServerHttpResponse response = exchange.getResponse();
        // 访问控制 - 黑白名单
        if (!IP_WHITE_LIST.contains(sourceAddress)) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return response.setComplete();
        }
        HttpHeaders headers = request.getHeaders();
        String body = headers.getFirst("body");
        String accessKey = headers.getFirst("accessKey");
        String timestamp = headers.getFirst("timestamp");
        String sign = headers.getFirst("sign");
        // 请求头中参数必须完整
        if (StringUtils.isAnyBlank(body, sign, accessKey, timestamp)) {
            throw new BusinessException(ErrorCode.FORBIDDEN_ERROR);
        }
        // 防重放 XHR
        long currentTime = System.currentTimeMillis() / 1000;
        assert timestamp != null;
        if (currentTime - Long.parseLong(timestamp) >= FIVE_MINUTES) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "会话已过期，请重试！");
        }
        try {
            User user = innerUserService.getInvokeUser(accessKey);
            if (user == null) {
                throw new BusinessException(ErrorCode.FORBIDDEN_ERROR, "账号不存在");
            }
            // 校验 accessKey
            if (!user.getAccessKey().equals(accessKey)) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "凭证有误或不存在，请重新获取凭证");
            }
            // 校验签名
            if (!SignUtils.genSign(body, user.getSecretKey()).equals(sign)) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "凭证无效，请重新获取凭证");
            }
            String path = INTERFACE_HOST + request.getPath().value();
            String method = Objects.requireNonNull(request.getMethod()).toString();

            if (StringUtils.isAnyBlank(path, method)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR);
            }
            InterfaceInfo interfaceInfo = innerInterfaceInfoService.getInterfaceInfo(path, method);
            if (interfaceInfo == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "接口不存在");
            }
            if (interfaceInfo.getStatus() == InterfaceInfoStatusEnum.OFFLINE.getValue()){
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "接口未开启");
            }
            String requestParams = interfaceInfo.getRequestParams();
            // 校验请求参数
            if (StringUtils.isBlank(requestParams)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数有误");
            }
            return handleResponse(exchange, chain, user.getId(), interfaceInfo.getId());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.FORBIDDEN_ERROR, e.getMessage());
        }
    }

    @Override
    public int getOrder() {
        return -1;
    }

    public Mono<Void> handleNoAuth(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return response.setComplete();
    }

    public Mono<Void> handleInvokeError(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
        return response.setComplete();
    }
}