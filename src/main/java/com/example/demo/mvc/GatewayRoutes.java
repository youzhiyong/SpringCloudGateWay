package com.example.demo.mvc;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import io.netty.buffer.ByteBufAllocator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Description:
 * Date: 2019-09-11
 *
 * @author youzhiyong
 */
@Configuration
public class GatewayRoutes {

    @Value("${gateWay.uri}")
    private String uri;

    @Value("${gateWay.path}")
    private String path;

    /**
     * 定义路由规则
     * 路由规则中有个readBody()方法：
     *      该方法会读取 post请求体中的参数并且设置到 exchange 的attribute中，key为 cachedRequestBodyObject
     *      然后在filter中就可以通过 exchange.getAttribute("cachedRequestBodyObject");
     *
     * 读取filter的传统方法：在request中读取，然后构造新的request往下传递(request中的body数据只能读取一次)
     *
     * @param builder
     * @return
     */
    @Bean
    public RouteLocator routeLocator(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("route1",
                        r -> r
                                .method(HttpMethod.GET)
                                .and()
                                .path(path)
                                .uri(uri))
                .route("route2",
                        r -> r
                                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                                .and()
                                .method(HttpMethod.POST)
                                .and()
                                .readBody(String.class, readBody -> {
                                    System.out.println("request method POST, Content-Type is application/x-www-form-urlencoded, body  is:{}" + readBody);
                                    //这里会读取 post请求体中的参数并且设置到 exchange 的attribute中，key为 cachedRequestBodyObject
                                    //然后在filter中就可以通过 exchange.getAttribute("cachedRequestBodyObject");
                                    return true;
                                })
                                .and()
                                .path(path)
                                .uri(uri))
                .route("route3",
                        r -> r
                                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                                .and()
                                .method(HttpMethod.POST)
                                .and()
                                .readBody(Object.class, readBody -> {
                                    System.out.println("request method POST, Content-Type is application/json, body  is:{}" + readBody);
                                    return true;
                                })
                                .and()
                                .path(path)
                                .uri(uri))
                .route("router",
                        r -> r
                                .uri(uri)
                                // 自定义的GateWayFilter 需要这样设置
                                .filter((exchange, chain) -> chain.filter(exchange))
                )
                .build();
    }

    /**
     * GatewayFilter 该filter需要配置在具体的router中才能生效
     * @return
     */
    @Bean
    public GatewayFilter filter() {
        return ((exchange, chain) -> chain.filter(exchange));
    }

    /**
     * GlobalFilter 该filter不需要单独配置
     * 全局权限验证过滤器
     * @return
     */
    @Bean
    @Order(-1)
    public GlobalFilter authorization() {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();

            String method = request.getMethodValue();

            if ("POST".equalsIgnoreCase(method)) {
                /*//从请求里获取Post请求体
                String bodyStr = resolveBodyFromRequest(request);

                JSONObject jsonObject = JSON.parseObject(bodyStr);
                Map map = jsonObject.getInnerMap();
                //todo check

                //下面的将请求体再次封装写回到request里，传到下一级，否则，由于请求体已被消费，后续的服务将取不到值
                ServerHttpRequest requestNew = buildNewRequest(request, bodyStr);

                //封装request，传给下一级
                return chain.filter(exchange.mutate().request(requestNew).build());*/

                Object requestBody = exchange.getAttribute("cachedRequestBodyObject");
                System.out.println("request body is:{}" + requestBody);

                return chain.filter(exchange).then(Mono.fromRunnable(() -> {
                    System.out.println("RequestFilter post filter");
                }));
            } else if ("GET".equalsIgnoreCase(method)){
                Map requestQueryParams = request.getQueryParams();


            }
            return chain.filter(exchange);
        };
    }

    /**
     * 从Flux<DataBuffer>中获取字符串的方法
     * spring-cloud-starter-gateway 2.0.6.RELEASE 有效， 2.1.8版本无效，取不到body中的值
     * @return 请求体
     */
    private String resolveBodyFromRequest(ServerHttpRequest serverHttpRequest) {
        //获取请求体
        Flux<DataBuffer> body = serverHttpRequest.getBody();

        AtomicReference<String> bodyRef = new AtomicReference<>();
        body.subscribe(buffer -> {
            CharBuffer charBuffer = StandardCharsets.UTF_8.decode(buffer.asByteBuffer());
            DataBufferUtils.release(buffer);
            bodyRef.set(charBuffer.toString());
        });
        //获取request body
        return bodyRef.get();
    }

    /**
     * 构造新的request
     * @param oldRequest
     * @param body
     * @return
     */
    private ServerHttpRequest buildNewRequest(ServerHttpRequest oldRequest, String body) {
        //下面的将请求体再次封装写回到request里，传到下一级，否则，由于请求体已被消费，后续的服务将取不到值
        URI uri = oldRequest.getURI();
        ServerHttpRequest requestNew = oldRequest.mutate().uri(uri).build();
        DataBuffer bodyDataBuffer = stringBuffer(body);
        Flux<DataBuffer> bodyFlux = Flux.just(bodyDataBuffer);

        requestNew = new ServerHttpRequestDecorator(requestNew) {
            @Override
            public Flux<DataBuffer> getBody() {
                return bodyFlux;
            }
        };
        return requestNew;
    }

    private DataBuffer stringBuffer(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);

        NettyDataBufferFactory nettyDataBufferFactory = new NettyDataBufferFactory(ByteBufAllocator.DEFAULT);
        DataBuffer buffer = nettyDataBufferFactory.allocateBuffer(bytes.length);
        buffer.write(bytes);
        return buffer;
    }

}
