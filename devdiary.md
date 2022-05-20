# Dev Diary
## Todo
* Make flags booleans rather than numbers
* Introduce Corkscrew helper
* Change calc model to 'calculation name' and 'worksheet name'
* Namespace rows by calulation and sheet
* Model compilation should through if validations fail. Maybe with assertions?
* 'add totals to calculation' function

## 20th May 2022
My stopping point yesterday was a working model for the Forest FMWC scenario. Some observations:

* The model is very slow for such a simple model of 56 rows: about 0.5 seconds for 25 periods (250 is 2.5secs, so not exactly linear)
* There was a lot of moving around the sheet. This is where namespacing and sheet separation would be useful.
* Most of the calculation validations were ditched as not very useful (and need to be cleaned up)
* In particular I removed the constraint that every calculations can only have one Export. I don't think this is especially bad, but it may have to be revisited, since it's not bad in _principle_.
* The import declaration idea was fully ditched, since it was fiddly and added no value. Instead there is a model check which looks for dependencies which are not either inputs or exported.  
* calculation / category thing is getting a bit scruffy, might have to look at that again.
* I couldn't get the error handling right. Sometimes the throws wouldn't fire. I'm assuming this is to do with Lazyness. I'd like the model to just not compile if there are problems. Maybe bring in spec here? 
* The "compilation" of the model from calculations/inputs->rows->graph I think works pretty well. Rows are clearly the 'fundamental unit' of this.
* The 'print-table' idea just doesn't work very well. Something better is needed. The whole display process / feedback loop needs rethinking.
* Make better use of namespaces. Calculations all get name-spaced on compiliation. Unqualified references in calculations are inferred 'locally', qualified ones are exports 
* Some potential useful 'special forms':
  * Sum everything in this calculation (for totals). Or maybe on compiliation, a '[name]-total' is created which sums everything. This would only be appropriate for a few things
  * Something about interpretting flags appropriately, so you can use it like a multiplier? I didn't actually use this much, it might just be better to have flags as booleans rather than numbers, to avoid `make-flag` and `flagged?`
  * helpers for creating corkscrews, something like

```clojure
(corkscrew :debt {:increase [:debt-drawdown]
                  :decrease [:debt-repayment]})
=>
{:name :debt-corkscrew
 :rows {:start         {:calculator [:debt :prev]}
        :increase      {:calculator [:debt-drawdown]}
        :decrease      {:calculator [:debt-repayment]}
        :ending-debt   {:calculator '(- (+ [:starting-debt]
                                       [:debt-increases])
                                       [:debt-decreases])}}}

(add-total my-calc)
=>
{:name :abc
 :rows {,,,
        :abc-total {:calculation '(+ [:thinga] [:thingb] ,,,)}}}
```
* looking at dependency chains was less useful than I thought it would be - though it did help me track down a nasty circular dependency.
* Should probably spec the models
* Maybe a rule about only referencing previous things _locally_? This might cause circularity problems