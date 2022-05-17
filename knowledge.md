# Knowledge Is Power
A detailed revenue modeling challenge.

The company is _Oxbridge Inc._. The product is an online school app allowing users to take classes online in the US and Australia. Revenues come from selling subscriptions on a monthly payment plan. There are 5 pricing plans: Dot, Line, Triangle, Square, Star.

Oxbridge reports in USD, ut given AUD revenues has some FX flux.

The first goal is to create a 5 year sales projection with a monthly grain, starting on 1 Jan 2022.

## Price rises
* Price rises are in $5 or AU$5 increments. (can be multiples, e.g. $10)
* A price will rise if the inflation adjusted price (I) is closer to X+$5 than X, where is is current price, i.e. when I>X+2.5
* Inflation is recalced each month
* A price will also only change if two others in that territory will also change.

## Data provided
* Starting users of territory/plan
* USD/AUD FX rates
* Inflation rates for US/AU
* Monthly user growth rates
 