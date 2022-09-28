package com.web.provider;

import lombok.AllArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;

import org.springframework.core.io.Resource;


@Component
public class ReverseProxyProvider {
    @Value("${targetServer:}")
    public String targetServer;

    public MultiValueMap<String, String> copyForm(HttpServletRequest request) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        Map<String, String[]> srcReq = request.getParameterMap();
        for (String k : srcReq.keySet()) {
            form.put(k, Arrays.asList(srcReq.get(k)));
        }
        return form;
    }


    public HttpHeaders copyHeaders(HttpServletRequest request) {
        // 复制请求头部
        HttpHeaders httpHeaders = new HttpHeaders();
        Enumeration<String> hne = request.getHeaderNames();
        while (hne.hasMoreElements()) {
            String hk = hne.nextElement();
            httpHeaders.add(hk, request.getHeader(hk));
        }
        return httpHeaders;
    }


    public String getTargetUrl(HttpServletRequest request) {
        // 去除api前缀
        String urlTo = targetServer + request.getRequestURI().substring(4);
        if (request.getQueryString() != null) {
            urlTo = urlTo + "?" + request.getQueryString();
        }
        return urlTo;
    }

    public String readReqBody(HttpServletRequest request) throws IOException {
        BufferedReader br = request.getReader();
        StringBuilder wholeStr = new StringBuilder();
        String str;
        while ((str = br.readLine()) != null) {
            wholeStr.append(str);
        }
        return wholeStr.toString();
    }

    @Deprecated
    public ResponseEntity<Resource> exchange(String url, String method, HttpEntity<Object> reqEntity) {
        RestTemplate restTemplate = new RestTemplate();
        return restTemplate.exchange(url, Objects.requireNonNull(HttpMethod.resolve(method)), reqEntity, Resource.class);
    }

    @Deprecated
    public void sendRsp(ResponseEntity<Resource> reqRsp, HttpServletResponse response) throws IOException {
        for (String k : reqRsp.getHeaders().keySet()) {
            for (String v : Objects.requireNonNull(reqRsp.getHeaders().get(k))) {
                response.setHeader(k, v);
            }
        }
        //这里理解错误了 Resource 还是把数据加载到内存里面了，暂时先不考虑内存的问题。
        InputStream input = Objects.requireNonNull(reqRsp.getBody()).getInputStream();
        OutputStream output = response.getOutputStream();
        StreamUtils.copy(input, output);
        input.close();
        output.close();
    }

    public void copyRspHeaders(HttpServletResponse response, HttpHeaders headers) {
        if (headers == null) {
            return;
        }
        for (String k : headers.keySet()) {
            for (String v : Objects.requireNonNull(headers.get(k))) {
                response.setHeader(k, v);
            }
        }
    }

    public void proxyWithoutBuffering(String url, String method, HttpServletResponse response, HttpEntity<Object> reqBody) throws IOException {
        RestTemplate restTemplate = new RestTemplate();

        final RequestCallback requestCallBack = restTemplate.httpEntityCallback(reqBody, null);

        final ResponseExtractor responseExtractor = (ClientHttpResponse clientHttpResponse) -> {
            copyRspHeaders(response, clientHttpResponse.getHeaders());
            StreamUtils.copy(clientHttpResponse.getBody(), response.getOutputStream());
            return null;
        };
        restTemplate.execute(url, HttpMethod.resolve(method), requestCallBack, responseExtractor);
    }


    public void proxy(HttpServletRequest request, HttpServletResponse response, Map<String, String> addFrom) throws Exception {

        String urlTo = this.getTargetUrl(request);

        // 复制请求头部
        HttpHeaders httpHeaders = copyHeaders(request);
        // 复制表单
        MultiValueMap<String, String> toReq = this.copyForm(request);

        // 额外追加的表单参数
        if (addFrom != null) {
            for (String fk : addFrom.keySet()) {
                toReq.put(fk, Collections.singletonList(addFrom.get(fk)));
            }
        }
        HttpEntity<Object> reqEntity = new HttpEntity<>(toReq, httpHeaders);

        // json body
        String wholeStr = this.readReqBody(request);
        if (!wholeStr.equals("")) {
            reqEntity = new HttpEntity<>(wholeStr, httpHeaders);
        }

        String m = request.getMethod();
        try {
            /*
            ResponseEntity<Resource> rsp = this.exchange(urlTo, m, reqEntity);
            this.sendRsp(rsp, response);
            */
            proxyWithoutBuffering(urlTo, m, response, reqEntity);

        } catch (HttpStatusCodeException e) {
            response.setStatus(e.getRawStatusCode());
            copyRspHeaders(response, e.getResponseHeaders());
            response.getWriter().write(e.getResponseBodyAsString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
