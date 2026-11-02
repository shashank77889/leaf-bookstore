let checkoutScript;
export function loadRazorpay(){
  if(window.Razorpay)return Promise.resolve();
  if(checkoutScript)return checkoutScript;
  checkoutScript=new Promise((resolve,reject)=>{
    const script=document.createElement('script');
    script.src='https://checkout.razorpay.com/v1/checkout.js';script.async=true;
    const timeout=setTimeout(()=>{script.remove();checkoutScript=null;reject(new Error('Razorpay did not load. Check your connection and try again.'));},20000);
    script.onload=()=>{clearTimeout(timeout);if(window.Razorpay)resolve();else{checkoutScript=null;reject(new Error('Razorpay checkout is unavailable.'));}};
    script.onerror=()=>{clearTimeout(timeout);script.remove();checkoutScript=null;reject(new Error('Razorpay could not load. Check your connection.'));};
    document.head.appendChild(script);
  });return checkoutScript;
}
export async function openRazorpay(order,user){
  await loadRazorpay();
  return new Promise((resolve,reject)=>{
    let completed=false,failed=false;
    const checkout=new window.Razorpay({key:order.keyId,amount:order.amount,currency:order.currency,order_id:order.providerOrderId,
      name:'Leaf Bookstore',description:'Book order · test payment',prefill:{name:user.name,email:user.email},theme:{color:'#18354c'},
      handler:response=>{completed=true;resolve(response);},
      modal:{ondismiss:()=>{if(!completed)reject(new Error(failed?'Payment was not completed. Retry or check its status in My orders.':'Checkout closed. Your pending order is saved in My orders; no order is confirmed yet.'));}}
    });checkout.on('payment.failed',()=>{failed=true;});checkout.open();
  });
}
