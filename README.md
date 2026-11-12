# Leaf bookstore

A working local e-commerce portfolio project built with **React 19, Java 21, Spring Boot 4.1, Spring Security, JDBC, and a persistent H2 database**.

## Start

Requirements: Java 21+, Maven 3.9+, Node.js 22+, npm.

From this project directory in PowerShell:

```powershell
.\build.ps1
.\start.ps1
```

Open **http://127.0.0.1:8080**. The packaged Spring Boot app serves both the React website and the API, so only one server is required. Stop it with Ctrl+C. If PowerShell blocks local scripts, run `powershell -ExecutionPolicy Bypass -File .\start.ps1` for this invocation.

The app is bound to the local computer by default. It has not been published to the internet.

## Try it

1. Browse, search, filter, or sort the catalogue and open a book's details.
2. Add books to your bag and adjust quantities.
3. Create your own customer account during checkout.
4. Enter sample delivery details with a six-digit Indian PIN code.
5. Try a successful or declined **demo payment**.
6. Open My orders to see the saved order.
7. Sign out, log in as the demo admin, and open Admin to manage the catalogue and move orders from confirmed to shipped to delivered.

### Local demo admin

- Email: `admin@leaf.local`
- Password: `LeafAdmin!2026`

To use a different initial admin password, set `$env:ADMIN_PASSWORD = 'your-new-password'` before the first launch. The seed runs only when that admin account does not already exist. Changing this environment variable does not reset an existing account's password.

## Implemented

- Responsive storefront, book details, search, category filters, price/title sorting.
- Device-local shopping bag, quantity controls, free delivery at ₹999.
- Customer registration and login with bcrypt-hashed passwords.
- HTTP-only session cookies, CSRF protection, session rotation on login.
- Server-side order pricing, input validation, inventory checks and atomic checkout.
- Idempotent checkout prevents duplicate orders on retried requests.
- Row locks prevent two customers from purchasing the same last copy.
- Persistent orders and customer-only order history.
- Role-protected admin: create/edit products, change stock, hide/show products, manage order status.
- Persistent file-based H2 database in `backend/data/` when launched using start.ps1.
- Order line items preserve the title and price paid when products are later edited.

## Payments

Razorpay Standard Checkout is integrated in test-only mode with server-created orders, signature verification, capture checks, webhooks, pending-order recovery, and stock reservations. Valid Razorpay test API keys are required to connect your account; no live or sandbox transaction has been verified with a merchant account yet. See [PAYMENT_SETUP.md](PAYMENT_SETUP.md) for local configuration. The separately labelled simulation option remains available without keys.

## Development

Terminal 1:

```powershell
cd backend
mvn.cmd spring-boot:run
```

Terminal 2:

```powershell
cd frontend
npm.cmd ci
npm.cmd run dev
```

Open http://127.0.0.1:5173. Vite proxies `/api` to port 8080. Rebuild with `build.ps1` to update the packaged website. Use the same hostname consistently; localhost and 127.0.0.1 have separate browser storage/cookies.

## Verification

After building the backend:

```powershell
node scripts/test-api.mjs
```

This starts an isolated in-memory database on port 18081 and checks authentication, CSRF, role enforcement, order ownership, declined payments, server-calculated totals, idempotency, stock limits, concurrent checkout, admin edits, and order transitions. It does not modify your normal shop database.

## API overview

| Endpoint | Access | Purpose |
|---|---|---|
| `GET /api/products` | Public | Visible catalogue |
| `GET /api/csrf` | Public | CSRF token for state changes |
| `GET /api/auth/me` | Public | Current session |
| `POST /api/auth/register` | Public + CSRF | Register customer |
| `POST /api/auth/login` | Public + CSRF | Sign in |
| `POST /api/auth/logout` | Signed in + CSRF | Sign out |
| `GET /api/orders` | Customer/admin | Own order history |
| `POST /api/orders` | Customer/admin + CSRF | Atomic demo checkout |
| `GET, POST /api/admin/products` | Admin | List/create products |
| `PUT /api/admin/products/{id}` | Admin | Update product |
| `PATCH /api/admin/products/{id}/visibility` | Admin | Show/hide product |
| `GET /api/admin/orders` | Admin | All orders |
| `PATCH /api/admin/orders/{id}` | Admin | Advance fulfilment status |
| `POST /api/admin/orders/{id}/review` | Admin | Settle a late payment: `accept` (take stock, confirm) or `refund` (full Razorpay refund) |

Prices are integer paise. ₹449 is stored as 44900. Delivery is ₹59 below ₹999 and free at or above that amount. Database result fields use uppercase names; authentication DTOs use lowercase names.

## Project structure

- `frontend/src/main.jsx` — React components, views, and API client.
- `frontend/src/styles.css` — responsive theme and interaction styles.
- `backend/src/main/java/com/leaf/store/` — application, security, REST API, seed data.
- `backend/src/main/resources/schema.sql` — SQL schema.
- `scripts/test-api.mjs` — isolated integration verification.
- `build.ps1`, `start.ps1` — build and local startup.

For a production release, also add account recovery, email verification, login rate limiting, operational monitoring, database backups/migrations, HTTPS/secure cookies, and a production database. Replace the known demo admin credentials. Current address forms are scoped to India. Fonts fall back to system fonts if Google Fonts is unavailable.

## Named runnable package

The `Leaf Bookstore` directory contains `Leaf Bookstore.cmd`, `Leaf Bookstore.jar`, and `START HERE.txt`. Double-click the `.cmd` launcher, wait for startup, and visit http://127.0.0.1:8080. This package only requires Java 21 or newer. It stores data in its own `Leaf Bookstore/data` directory, separate from the source development database. Run only one server on port 8080 at a time.

