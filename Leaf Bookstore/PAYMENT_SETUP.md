# Connect Razorpay test checkout

The code now integrates Razorpay Standard Checkout, server-created orders, HMAC signature verification, server-side payment fetching/capture, signed webhooks, and periodic payment reconciliation. It is **test-only**: `rzp_live_` keys are rejected.

## Configure your account

1. Open the [Razorpay Dashboard](https://dashboard.razorpay.com/) and select **Test Mode**.
2. Generate test API keys under your account's API Keys settings. Refer to [Razorpay's official integration guide](https://razorpay.com/docs/payments/payment-gateway/web-integration/standard/integration-steps/).
3. Open `Leaf Bookstore/payment.properties` locally and fill in:

```properties
store.razorpay.key-id=rzp_test_YOUR_KEY_ID
store.razorpay.key-secret=YOUR_TEST_KEY_SECRET
store.razorpay.webhook-secret=
```

4. Stop the running app with Ctrl+C, then double-click `Leaf Bookstore.cmd` again. Refresh the browser.
5. Add a book, sign in, and choose **Razorpay · test mode** in checkout. Use Razorpay's documented test payment details, not a real card.

Do not paste your secret key into chat or put it in React code. The `payment.properties` file and the database are excluded from Git. When running the source backend instead of the packaged app, put the file inside `backend/`. Environment variables `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, and `RAZORPAY_WEBHOOK_SECRET` are also supported if the corresponding entries are not overridden by a local file.

## What works without keys

The shop remains usable and an explicitly labelled **Simulation · no gateway** option is retained. Razorpay itself remains unavailable until valid test keys are supplied. Configuring placeholders does not connect an account.

## Payment lifecycle

- Prices, delivery, and INR amounts come from the server, in integer paise.
- A pending order reserves inventory for 30 minutes. Retrying the same checkout reuses that order.
- The client uses the official `https://checkout.razorpay.com/v1/checkout.js` script. No card details pass through Leaf's backend.
- The browser response is checked using HMAC-SHA256 against the stored provider order ID, then the backend fetches the provider payment and validates order ID, amount, currency, and captured status.
- Authorized payments are captured only while the reservation is held. Only captured payments become confirmed orders.
- Closing checkout or a failed payment leaves a pending order. Customers can continue payment or check status from **My orders**.
- A background reconciliation job runs every minute while the app is running. It checks up to 100 pending/expired records per cycle. This is a small portfolio application, not a high-volume production payment processor.
- Unpaid expired reservations are released only after a successful provider status check. Outages conservatively retain stock until reconciliation recovers.
- A late captured payment after stock has been released becomes **Payment review**, never an automatically fulfilled order. Review/refund it in the Razorpay test dashboard. Automated refunds and refund tracking are not implemented.
- Admins cannot ship pending, expired, or review orders.

## Optional webhooks

Local checkout does not require a public webhook: browser verification, the status button, and polling cover local testing. For a hosted HTTPS backend, configure Razorpay test webhooks to:

```
https://YOUR_BACKEND/api/payments/webhook
```

Subscribe to `payment.authorized`, `payment.captured`, and `order.paid`. Set the same webhook secret in Razorpay and `store.razorpay.webhook-secret`. The webhook secret is separate from the API secret. Only this endpoint is exempt from browser CSRF; it verifies the raw request body signature before acting. Duplicate events are safe. Razorpay cannot call a localhost URL directly.

## Validation and limits

Run `mvn.cmd test` in `backend` for payment service, signature, webhook, and HTTP-client tests against fixtures. Run `node scripts/test-api.mjs` from the project root after a build for the existing end-to-end API checks. Tests do not call Razorpay or move money. A real Razorpay sandbox transaction still needs your account's test keys and must be verified after configuration.

This project intentionally rejects live credentials. Going live requires a separate production-readiness pass, actual products and fulfilment, account activation, HTTPS, secure session cookies, stronger account controls, monitoring, reliable reconciliation at scale, and a refund workflow.
