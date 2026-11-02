package com.leaf.store;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class RazorpayClient {
    private final String keyId,secret,webhookSecret,base;
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    RazorpayClient(@Value("${store.razorpay.key-id}") String keyId,
        @Value("${store.razorpay.key-secret}") String secret,
        @Value("${store.razorpay.webhook-secret}") String webhookSecret,
        @Value("${store.razorpay.api-base}") String base) {
        this.keyId=keyId.trim();this.secret=secret.trim();this.webhookSecret=webhookSecret;this.base=base.replaceAll("/+$", "");
        // This portfolio shop is deliberately test-only; live credentials are rejected.
        if(!this.keyId.isEmpty()&&!this.keyId.startsWith("rzp_test_")) throw new IllegalArgumentException("Leaf Bookstore accepts Razorpay TEST keys only.");
        URI uri=URI.create(this.base);
        if(!this.base.equals("https://api.razorpay.com/v1")&&!Set.of("127.0.0.1","localhost").contains(uri.getHost()))
            throw new IllegalArgumentException("Unsupported payment API host.");
    }
    public boolean configured(){return keyId.startsWith("rzp_test_")&&!keyId.contains("REPLACE")&&!secret.isBlank()&&!secret.equals("REPLACE_ME");}
    public String keyId(){return keyId;}
    public void requireConfigured(){if(!configured())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Razorpay test keys have not been configured. Add them to the local payment.properties file and restart the store.");}
    public boolean checkoutSignature(String orderId,String paymentId,String signature){return signature(secret,orderId+"|"+paymentId,signature);}
    public boolean webhookSignature(String raw,String signature){return !webhookSecret.isBlank()&&signature(webhookSecret,raw,signature);}
    static boolean signature(String secret,String payload,String supplied){
        if(supplied==null||!supplied.matches("[a-fA-F0-9]{64}"))return false;
        try {var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return MessageDigest.isEqual(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)),HexFormat.of().parseHex(supplied));}
        catch(Exception e){return false;}
    }
    JsonNode createOrder(int amount,String receipt){return request("POST","/orders",Map.of("amount",amount,"currency","INR","receipt",receipt));}
    JsonNode payment(String id){return request("GET","/payments/"+safeId(id),null);}
    JsonNode payments(String id){return request("GET","/orders/"+safeId(id)+"/payments",null);}
    JsonNode capture(String id,int amount){return request("POST","/payments/"+safeId(id)+"/capture",Map.of("amount",amount,"currency","INR"));}
    JsonNode parse(String raw){return json.readTree(raw);}
    String safeId(String id){if(id==null||!id.matches("[A-Za-z0-9_]{1,100}"))throw StoreController.bad("Invalid payment reference.");return id;}
    JsonNode request(String method,String path,Object body){
        requireConfigured();
        try {
            var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(20))
                .header("Authorization","Basic "+Base64.getEncoder().encodeToString((keyId+":"+secret).getBytes(StandardCharsets.UTF_8)))
                .header("Content-Type","application/json");
            builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()<200||response.statusCode()>=300)throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Razorpay could not complete the request. Check your test keys or retry from My orders. Do not pay again if payment already succeeded.");
            return json.readTree(response.body());
        }catch(ResponseStatusException ex){throw ex;}catch(InterruptedException ex){Thread.currentThread().interrupt();throw unavailable();}catch(Exception ex){throw unavailable();}
    }
    ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Payment service is temporarily unavailable. Check payment status in My orders before trying again.");}
}
