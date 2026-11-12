package com.leaf.store;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:payments;DB_CLOSE_DELAY=-1","store.razorpay.poll-ms=3600000"})
class PaymentServiceTest {
    @Autowired PaymentService payments;
    @Autowired JdbcTemplate db;
    @Autowired StoreController store;
    @MockitoBean RazorpayClient gateway;
    Authentication user=new UsernamePasswordAuthenticationToken("paytest@example.test",null,List.of());
    ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup(){
        db.update("DELETE FROM order_items");db.update("DELETE FROM shop_orders");
        db.update("DELETE FROM accounts WHERE email IN ('paytest@example.test','otherpay@example.test')");
        db.update("INSERT INTO accounts(name,email,password_hash,role) VALUES('Payment Tester','paytest@example.test','unused','CUSTOMER')");
        db.update("INSERT INTO accounts(name,email,password_hash,role) VALUES('Other Tester','otherpay@example.test','unused','CUSTOMER')");
        db.update("UPDATE products SET stock=18,price=44900,active=TRUE WHERE id=1");
        when(gateway.keyId()).thenReturn("rzp_test_fixture");
        when(gateway.createOrder(eq(50800),anyString())).thenReturn(node("{\"id\":\"order_fixture\",\"amount\":50800,\"currency\":\"INR\"}"));
        when(gateway.safeId(anyString())).thenAnswer(i->i.getArgument(0));
    }
    JsonNode node(String s){return json.readTree(s);}
    StoreController.Checkout checkout(){return new StoreController.Checkout(List.of(new StoreController.Item(1,1)),"request-1","Reader","12 Sample Road","Pune","411001","success");}
    Map<String,Object> create(){return payments.create(checkout(),user);}
    JsonNode payment(String status,int amount,String currency,String order){return node("{\"id\":\"pay_fixture\",\"order_id\":\""+order+"\",\"amount\":"+amount+",\"currency\":\""+currency+"\",\"status\":\""+status+"\",\"captured\":"+status.equals("captured")+"}");}
    int stock(){return db.queryForObject("SELECT stock FROM products WHERE id=1",Integer.class);}
    Map<String,Object> stored(String id){return db.queryForMap("SELECT * FROM shop_orders WHERE id=?",id);}
    void payList(JsonNode payment){when(gateway.payments("order_fixture")).thenReturn(node("{\"items\":["+payment.toString()+"]}"));}
    void expire(String id){db.update("UPDATE shop_orders SET reserved_until=DATEADD('MINUTE',-1,CURRENT_TIMESTAMP) WHERE id=?",id);}
    @Test void createsServerPricedOrderAndReservesStock(){var c=create();assertEquals(50800,c.get("amount"));assertEquals(17,stock());assertEquals("PENDING_PAYMENT",stored((String)c.get("id")).get("STATUS"));assertFalse(c.containsKey("keySecret"));}
    @Test void repeatedCreateUsesOneProviderOrderAndReservation(){var a=create();var b=create();assertEquals(a.get("id"),b.get("id"));assertEquals(17,stock());verify(gateway,times(1)).createOrder(anyInt(),anyString());}
    @Test void providerFailureRollsBackReservation(){when(gateway.createOrder(anyInt(),anyString())).thenThrow(new IllegalStateException("fixture outage"));assertThrows(IllegalStateException.class,this::create);assertEquals(18,stock());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM shop_orders",Integer.class));}
    @Test void noTransactionIsOpenWhileCallingProvider(){
        var open=new boolean[]{true};
        when(gateway.createOrder(eq(50800),anyString())).thenAnswer(i->{
            open[0]=org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
            return node("{\"id\":\"order_fixture\",\"amount\":50800,\"currency\":\"INR\"}");
        });
        create();
        assertFalse(open[0]);
    }
    @Test void unexpectedProviderResponseReleasesReservation(){
        when(gateway.createOrder(anyInt(),anyString())).thenReturn(node("{\"id\":\"order_fixture\",\"amount\":1,\"currency\":\"INR\"}"));
        assertThrows(ResponseStatusException.class,this::create);
        assertEquals(18,stock());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM shop_orders",Integer.class));
    }
    @Test void retryFinishesCheckoutLeftWithoutProviderOrder(){
        String id=(String)store.reserve(checkout(),user).get("ID");
        var c=create();
        assertEquals(id,c.get("id"));
        assertEquals("order_fixture",stored(id).get("PROVIDER_ORDER_ID"));
        assertEquals(17,stock());
    }
    @Test void abandonedCheckoutWithoutProviderOrderExpiresWithoutCallingProvider(){
        String id=(String)store.reserve(checkout(),user).get("ID");
        assertEquals(17,stock());
        assertThrows(ResponseStatusException.class,()->payments.reconcile(id,user));
        expire(id);
        assertEquals("EXPIRED",payments.reconcile(id,user).get("STATUS"));
        payments.reconcile(id,null);
        assertEquals(18,stock());
        verify(gateway,never()).payments(anyString());
    }
    @Test void signatureMustBeValid(){var c=create();when(gateway.checkoutSignature(anyString(),anyString(),anyString())).thenReturn(false);assertThrows(ResponseStatusException.class,()->payments.verify(new PaymentController.Verification((String)c.get("id"),"order_fixture","pay_fixture","0".repeat(64)),user));assertEquals("PENDING_PAYMENT",stored((String)c.get("id")).get("STATUS"));verify(gateway,never()).payment(anyString());}
    @Test void callbackUsesStoredProviderOrderId(){var c=create();assertThrows(ResponseStatusException.class,()->payments.verify(new PaymentController.Verification((String)c.get("id"),"order_unrelated","pay_fixture","0".repeat(64)),user));verify(gateway,never()).checkoutSignature(anyString(),anyString(),anyString());}
    @Test void capturedPaymentConfirmsExactlyOnce(){var c=create();payList(payment("captured",50800,"INR","order_fixture"));var a=payments.reconcile((String)c.get("id"),user);var b=payments.reconcile((String)c.get("id"),user);assertEquals("CONFIRMED",a.get("STATUS"));assertEquals("RAZORPAY_TEST_PAID",b.get("PAYMENT"));assertEquals(17,stock());}
    @Test void authorizationMustBeCaptured(){var c=create();payList(payment("authorized",50800,"INR","order_fixture"));when(gateway.capture("pay_fixture",50800)).thenReturn(payment("captured",50800,"INR","order_fixture"));assertEquals("CONFIRMED",payments.reconcile((String)c.get("id"),user).get("STATUS"));verify(gateway).capture("pay_fixture",50800);}
    @Test void rejectsWrongAmount(){var c=create();payList(payment("captured",100,"INR","order_fixture"));assertThrows(ResponseStatusException.class,()->payments.reconcile((String)c.get("id"),user));assertEquals("PENDING_PAYMENT",stored((String)c.get("id")).get("STATUS"));}
    @Test void rejectsWrongCurrency(){var c=create();payList(payment("captured",50800,"USD","order_fixture"));assertThrows(ResponseStatusException.class,()->payments.reconcile((String)c.get("id"),user));}
    @Test void rejectsWrongProviderOrder(){var c=create();payList(payment("captured",50800,"INR","order_wrong"));assertThrows(ResponseStatusException.class,()->payments.reconcile((String)c.get("id"),user));}
    @Test void failedAttemptDoesNotConfirmOrder(){var c=create();payList(payment("failed",50800,"INR","order_fixture"));assertEquals("PENDING_PAYMENT",payments.reconcile((String)c.get("id"),user).get("STATUS"));}
    @Test void expirationReleasesStockOnlyOnce(){var c=create();String id=(String)c.get("id");expire(id);when(gateway.payments(anyString())).thenReturn(node("{\"items\":[]}"));assertEquals("EXPIRED",payments.reconcile(id,user).get("STATUS"));payments.reconcile(id,user);assertEquals(18,stock());}
    @Test void outageDoesNotReleasePotentiallyPaidStock(){var c=create();String id=(String)c.get("id");expire(id);when(gateway.payments(anyString())).thenThrow(new IllegalStateException("fixture outage"));assertThrows(IllegalStateException.class,()->payments.reconcile(id,user));assertEquals(17,stock());assertEquals("PENDING_PAYMENT",stored(id).get("STATUS"));}
    @Test void lateCaptureRequiresReviewWithoutTakingStockAgain(){var c=create();String id=(String)c.get("id");expire(id);when(gateway.payments(anyString())).thenReturn(node("{\"items\":[]}"));payments.reconcile(id,user);payList(payment("captured",50800,"INR","order_fixture"));assertEquals("PAYMENT_REVIEW",payments.reconcile(id,user).get("STATUS"));assertEquals(18,stock());}
    String inReview(){
        String id=(String)create().get("id");expire(id);
        when(gateway.payments(anyString())).thenReturn(node("{\"items\":[]}"));payments.reconcile(id,user);
        payList(payment("captured",50800,"INR","order_fixture"));payments.reconcile(id,user);
        assertEquals("PAYMENT_REVIEW",stored(id).get("STATUS"));assertEquals(18,stock());
        return id;
    }
    JsonNode refundResponse(int amount){return node("{\"id\":\"rfnd_fixture\",\"payment_id\":\"pay_fixture\",\"amount\":"+amount+"}");}
    @Test void acceptingReviewConfirmsOrderAndTakesStock(){
        String id=inReview();
        var order=payments.review(id,"accept");
        assertEquals("CONFIRMED",order.get("STATUS"));assertEquals("RAZORPAY_TEST_PAID",order.get("PAYMENT"));assertEquals(17,stock());
        verify(gateway,never()).refund(anyString(),anyInt());
    }
    @Test void acceptingReviewFailsWhenBookSoldOut(){
        String id=inReview();db.update("UPDATE products SET stock=0 WHERE id=1");
        assertThrows(ResponseStatusException.class,()->payments.review(id,"accept"));
        assertEquals("PAYMENT_REVIEW",stored(id).get("STATUS"));assertEquals(0,stock());
    }
    @Test void refundingReviewReturnsMoneyAndKeepsStock(){
        String id=inReview();
        when(gateway.payment("pay_fixture")).thenReturn(payment("captured",50800,"INR","order_fixture"));
        when(gateway.refund("pay_fixture",50800)).thenReturn(refundResponse(50800));
        var order=payments.review(id,"refund");
        assertEquals("REFUNDED",order.get("STATUS"));assertEquals("RAZORPAY_TEST_REFUNDED",order.get("PAYMENT"));assertEquals(18,stock());
        assertThrows(ResponseStatusException.class,()->payments.review(id,"refund"));
        verify(gateway,times(1)).refund("pay_fixture",50800);
    }
    @Test void paymentAlreadyRefundedAtProviderIsNotRefundedAgain(){
        String id=inReview();
        when(gateway.payment("pay_fixture")).thenReturn(payment("refunded",50800,"INR","order_fixture"));
        assertEquals("REFUNDED",payments.review(id,"refund").get("STATUS"));
        verify(gateway,never()).refund(anyString(),anyInt());
    }
    @Test void failedRefundLeavesOrderInReview(){
        String id=inReview();
        when(gateway.payment("pay_fixture")).thenReturn(payment("captured",50800,"INR","order_fixture"));
        when(gateway.refund("pay_fixture",50800)).thenThrow(new IllegalStateException("fixture outage"));
        assertThrows(IllegalStateException.class,()->payments.review(id,"refund"));
        assertEquals("PAYMENT_REVIEW",stored(id).get("STATUS"));
    }
    @Test void refundResponseMustMatchPayment(){
        String id=inReview();
        when(gateway.payment("pay_fixture")).thenReturn(payment("captured",50800,"INR","order_fixture"));
        when(gateway.refund("pay_fixture",50800)).thenReturn(refundResponse(100));
        assertThrows(ResponseStatusException.class,()->payments.review(id,"refund"));
        assertEquals("PAYMENT_REVIEW",stored(id).get("STATUS"));
    }
    @Test void reviewOnlyAppliesToOrdersInReview(){
        String id=(String)create().get("id");
        assertThrows(ResponseStatusException.class,()->payments.review(id,"accept"));
        assertThrows(ResponseStatusException.class,()->payments.review(id,"refund"));
        assertEquals("PENDING_PAYMENT",stored(id).get("STATUS"));assertEquals(17,stock());
    }
    @Test void otherCustomerCannotAccessPayment(){var c=create();var other=new UsernamePasswordAuthenticationToken("otherpay@example.test",null,List.of());assertThrows(ResponseStatusException.class,()->payments.resume((String)c.get("id"),other));assertThrows(ResponseStatusException.class,()->payments.reconcile((String)c.get("id"),other));}
    @Test void hmacRequiresExactBodyAndSecret(){String signature="f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8";assertTrue(RazorpayClient.signature("key","The quick brown fox jumps over the lazy dog",signature));assertFalse(RazorpayClient.signature("wrong","The quick brown fox jumps over the lazy dog",signature));assertFalse(RazorpayClient.signature("key","changed",signature));assertFalse(RazorpayClient.signature("key","body","bad"));}
    @Test void liveKeysAreRejected(){assertThrows(IllegalArgumentException.class,()->new RazorpayClient("rzp_live_example","secret","webhook","https://api.razorpay.com/v1"));}
    @Test void missingKeysCannotStartCheckout(){var client=new RazorpayClient("","","","https://api.razorpay.com/v1");assertFalse(client.configured());assertThrows(ResponseStatusException.class,client::requireConfigured);}
}
