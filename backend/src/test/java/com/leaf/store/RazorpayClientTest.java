package com.leaf.store;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class RazorpayClientTest {
    @Test void sendsAuthenticatedServerPricedOrderToProvider() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var captured=new ArrayList<String>();
        server.createContext("/v1/orders",exchange->{
            captured.add(exchange.getRequestMethod());captured.add(exchange.getRequestHeaders().getFirst("Authorization"));captured.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] body="{\"id\":\"order_test\",\"amount\":50800,\"currency\":\"INR\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try{
            var client=new RazorpayClient("rzp_test_fixture","fixture-secret","","http://127.0.0.1:"+server.getAddress().getPort()+"/v1");
            assertEquals("order_test",client.createOrder(50800,"receipt-test").path("id").asText());
            assertEquals("POST",captured.get(0));assertEquals("Basic "+Base64.getEncoder().encodeToString("rzp_test_fixture:fixture-secret".getBytes(StandardCharsets.UTF_8)),captured.get(1));
            var request=client.parse(captured.get(2));assertEquals(50800,request.path("amount").asInt());assertEquals("INR",request.path("currency").asText());assertEquals("receipt-test",request.path("receipt").asText());
        }finally{server.stop(0);}
    }
    @Test void webhookRequiresSignedRawBodyAndReconcilesStoredOrder() throws Exception {
        var client=new RazorpayClient("rzp_test_fixture","fixture-secret","webhook-fixture","https://api.razorpay.com/v1");
        var db=mock(JdbcTemplate.class);var service=mock(PaymentService.class);var controller=new PaymentController(client,service,db);
        String raw="{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"order_id\":\"order_fixture\"}}}}";
        assertThrows(ResponseStatusException.class,()->controller.webhook(raw,"0".repeat(64)));
        verifyNoInteractions(service,db);
        when(db.queryForList("SELECT id FROM shop_orders WHERE provider_order_id=?","order_fixture")).thenReturn(List.of(Map.of("ID","local-id")));
        var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec("webhook-fixture".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        String signature=HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
        controller.webhook(raw,signature);verify(service).reconcile("local-id",null);
        assertThrows(ResponseStatusException.class,()->controller.webhook(raw+" ",signature));
    }
}
