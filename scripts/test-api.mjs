import { spawn, spawnSync } from 'node:child_process';
import assert from 'node:assert/strict';
import { setTimeout as delay } from 'node:timers/promises';
const port=18081;
// Blank the Razorpay settings so a local backend/payment.properties never reaches this isolated test server.
const child=spawn('java',['-jar','target/leaf-store-1.0.0.jar','--store.razorpay.key-id=','--store.razorpay.key-secret=','--store.razorpay.webhook-secret='],{cwd:new URL('../backend/',import.meta.url),env:{...process.env,PORT:String(port),DATABASE_URL:'jdbc:h2:mem:leaf_test;DB_CLOSE_DELAY=-1'},stdio:['ignore','pipe','pipe']});
let logs='';child.stdout.on('data',d=>logs+=d);child.stderr.on('data',d=>logs+=d);
class Client {
  cookie='';csrf=null;
  async request(path,method='GET',body,includeCsrf=true){
    if(method!=='GET'&&includeCsrf&&!this.csrf)this.csrf=(await this.request('/csrf')).data;
    const res=await fetch(`http://127.0.0.1:${port}/api${path}`,{method,headers:{'Content-Type':'application/json',...(this.cookie?{Cookie:this.cookie}:{}),...(method!=='GET'&&includeCsrf?{[this.csrf.header]:this.csrf.token}:{})},body:body?JSON.stringify(body):undefined});
    const c=res.headers.getSetCookie().find(c=>c.startsWith('JSESSIONID='));if(c)this.cookie=c.split(';')[0];
    const text=await res.text();return {status:res.status,data:text?JSON.parse(text):null};
  }
}
let passed=0;
function check(label,fn){fn();console.log('PASS '+label);passed++;}
try{
  let ready=false;for(let i=0;i<90;i++){try{const r=await fetch(`http://127.0.0.1:${port}/api/products`);if(r.ok && (await r.json()).length===8){ready=true;break;}}catch{}await delay(500);}
  if(!ready)throw new Error('Backend did not start.\n'+logs);
  const guest=new Client(),customer=new Client(),admin=new Client(),other=new Client();
  let r=await guest.request('/products');check('Seeded catalogue has eight books',()=>assert.equal(r.data.length,8));const first=r.data[0],initialStock=first.STOCK;
  r=await guest.request('/orders');check('Anonymous orders are blocked',()=>assert.equal(r.status,401));
  r=await guest.request('/payments/config');check('Gateway config exposes no secrets',()=>{assert.equal(r.data.mode,'test');assert.equal(r.data.configured,false);assert.equal(Object.keys(r.data).length,3);});
  r=await guest.request('/payments/orders','POST',{});check('Gateway checkout requires authentication',()=>assert.equal(r.status,401));
  r=await guest.request('/payments/orders/no-such-order/checkout');check('Payment resume requires authentication',()=>assert.equal(r.status,401));
  r=await guest.request('/payments/webhook','POST',{event:'payment.captured'},false);check('Unsigned webhook is rejected',()=>assert.equal(r.status,400));
  r=await guest.request('/admin/products');check('Anonymous admin is blocked',()=>assert.equal(r.status,401));
  r=await guest.request('/auth/register','POST',{name:'Blocked',email:'blocked@example.test',password:'Password123!'},false);check('CSRF protects account creation',()=>assert.equal(r.status,403));
  r=await customer.request('/auth/register','POST',{name:'Test Reader',email:'reader@example.test',password:'Password123!'});check('Registration signs in customer',()=>{assert.equal(r.status,200);assert.equal(r.data.role,'CUSTOMER');});
  r=await customer.request('/admin/products');check('Customer cannot read admin data',()=>assert.equal(r.status,403));
  r=await customer.request('/admin/products','POST',{title:'Hack'});check('Customer cannot mutate admin data',()=>assert.equal(r.status,403));
  const body={items:[{id:first.ID,quantity:2}],requestKey:'order-one',recipient:'Test Reader',address:'12 Sample Lane',city:'Pune',postcode:'411001',outcome:'success'};
  r=await customer.request('/orders','POST',{...body,outcome:'decline'});check('Declined demo payment returns 402',()=>assert.equal(r.status,402));
  r=await customer.request('/products');check('Declined payment leaves inventory unchanged',()=>assert.equal(r.data[0].STOCK,initialStock));
  r=await customer.request('/orders','POST',body);const order=r.data;check('Order total calculated by server with delivery',()=>{assert.equal(r.status,200);assert.equal(order.TOTAL,first.PRICE*2+5900);assert.equal(order.PAYMENT,'DEMO_PAID');});
  r=await customer.request('/orders','POST',body);check('Duplicate checkout returns the same order',()=>assert.equal(r.data.ID,order.ID));
  r=await customer.request('/products');check('Stock decremented only once',()=>assert.equal(r.data[0].STOCK,initialStock-2));
  r=await customer.request('/orders','POST',{...body,requestKey:'too-many',items:[{id:first.ID,quantity:20}]});check('Insufficient inventory is rejected',()=>assert.equal(r.status,400));
  r=await customer.request('/orders','POST',{...body,requestKey:'negative',items:[{id:first.ID,quantity:-1}]});check('Negative quantities are rejected',()=>assert.equal(r.status,400));
  r=await customer.request('/orders','POST',{...body,requestKey:'invalid-pin',postcode:'1'});check('Invalid delivery details are rejected',()=>assert.equal(r.status,400));
  await other.request('/auth/register','POST',{name:'Other',email:'other@example.test',password:'Password123!'});
  r=await other.request('/orders');check('Orders are private to their owner',()=>assert.equal(r.data.length,0));
  r=await admin.request('/auth/login','POST',{email:'admin@leaf.local',password:'LeafAdmin!2026'});check('Admin login works',()=>assert.equal(r.data.role,'ADMIN'));
  r=await admin.request('/admin/orders');check('Admin can see customer orders',()=>assert.equal(r.data.length,1));
  r=await admin.request('/admin/orders/'+order.ID,'PATCH',{status:'DELIVERED'});check('Order status cannot skip shipment',()=>assert.equal(r.status,400));
  r=await admin.request('/admin/orders/'+order.ID,'PATCH',{status:'SHIPPED'});check('Admin can ship an order',()=>assert.equal(r.status,200));
  r=await customer.request('/orders');check('Customer sees updated order status',()=>assert.equal(r.data[0].STATUS,'SHIPPED'));
  const product={title:'Test Book',author:'Test Author',category:'Design',description:'A test book.',price:120000,stock:1,color:'#334455'};
  r=await admin.request('/admin/products','POST',product);check('Admin can create products',()=>assert.equal(r.status,200));
  r=await admin.request('/admin/products');const created=r.data.at(-1);
  const concurrent=await Promise.all([customer.request('/orders','POST',{...body,requestKey:'last-copy-a',items:[{id:created.ID,quantity:1}]}),other.request('/orders','POST',{...body,requestKey:'last-copy-b',items:[{id:created.ID,quantity:1}]})]);
  check('Concurrent customers cannot oversell the last copy',()=>assert.deepEqual(concurrent.map(r=>r.status).sort(),[200,400]));
  check('Free shipping is calculated for qualifying order',()=>assert.equal(concurrent.find(r=>r.status===200).data.SHIPPING,0));
  r=await admin.request('/admin/products/'+created.ID,'PUT',{...product,price:135000,stock:5});check('Admin can edit inventory and price',()=>assert.equal(r.status,200));
  r=await admin.request('/admin/products/'+created.ID+'/visibility','PATCH',{active:false});check('Admin can hide a product',()=>assert.equal(r.status,200));
  r=await guest.request('/products');check('Hidden products leave the storefront',()=>assert.ok(!r.data.some(b=>b.ID===created.ID)));
  r=await customer.request('/orders','POST',{...body,requestKey:'hidden-product',items:[{id:created.ID,quantity:1}]});check('Hidden products cannot be purchased',()=>assert.equal(r.status,400));
  r=await customer.request('/auth/logout','POST');check('Logout succeeds',()=>assert.equal(r.status,204));
  r=await customer.request('/orders');check('Logged out session cannot read orders',()=>assert.equal(r.status,401));
  console.log(`\n${passed} integration checks passed.`);
}catch(e){console.error(e);process.exitCode=1;}finally{if(process.platform==='win32'&&child.pid)spawnSync('taskkill',['/PID',String(child.pid),'/T','/F'],{stdio:'ignore'});else child.kill();child.stdout.destroy();child.stderr.destroy();child.unref();}



