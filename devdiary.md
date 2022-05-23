# Dev Diary
## Todo
* Better printing
  * Better selection of sheets, rather than calcs (i.e. select "fs" to select everything in the FS, including fs.assets)
  * change print-calcs so it prints in a single table
  * Do something about order
  * Dump to CSV
  * Dump to HTML?
* Isolate and show circular dependencies
* Check on print table where there are no rows, or you got the sheet name wrong
* Compile model to function on arrays to increase performance?
* Units and metadata
* Model report: 
  * number of rows
  * number of evaluations per period
  * Unused rows
* flag optimizations: when you've calculated the flags, you can store them in a set, then if an expression depends on the flag you can just look it up and avoid evaluating if the flag isn't true
* Circularity helpers, but only when it comes up
* Some sort of limited "sheet" recalculation. Everything in the ns is recalculated, but any external references are looked up in a cache

## 23rd May 2022
* **DONE** Have what you're working on drive what's calculated. e.g. I'm working on O&M costs, so only calculate the descendents of those rows
* **DONE** scatter graphing
* **DONE** line graphing
* **DONE** Print row and all its dependencies
* **DONE** Table rounding

## 22nd May 2022
### How could array compilation work?
I have an intuition that a model (at least the boolean and numerics) can be compiled down to a sort of array language, which would be much more performant that the map/record based version. I want to explore how that would work

1. You get the top-sort of the graph (x rows)
2. You figure out the number of periods (y columns)
3. You create a (transient, zero-intialized) x*y 2d array
4. You turn the model calculations into array operations

e.g. thing3 is `(+ [:thing1] [:thing2])` for period 10 becomes 

```clojure
(aset 2 10 
  (+ (aget 0 10) 
     (aget 1 10)))
```

I think you might be able to turn the (ordered) row definitions provided by the user into one giant function which calculates all the periods, like:

```clojure
(let [row-names [:model-period-number :first-period-flag
                 :compound-inflation :sale-price]
      num-periods (inc 10)
      num-rows 5
      rows (to-array-2d (repeat num-rows (repeat num-periods 0)))]
  (doseq [period (range 1 num-periods)]
    ;; 0 = model col num
    (aset rows 0 period
          (inc (aget rows 0 (dec period))))
    ;; 1 = first preiod flag
    (aset rows 1 period
          (if (= 1 (aget rows 0 period)) 1 0))
    ;; 2 = compound inflation
    (aset rows 2 period
          (Math/pow 1.02 (dec (aget rows 0 period))))
    ;; 3 = sale-price
    (aset rows 3 period (* (aget rows 2 period) 50)))
  (zipmap row-names (map vec rows)))
```

Taking row 2 as an example, this would require the following transformation:

```clojure
{:prices/compound-inflation 
  '(Math/pow [:inputs/inflation-rate]
             (dec [:model-column-number]))}

;; we get the row number 2 from the topsort
(aset rows 2 period
  (Math/pow 1.02 (dec (aget rows 0 period))))
```

## 21st May 2022

* **DONE** Make flags booleans rather than numbers
* **DONE** Introduce Corkscrew helper
* **DONE** 'add totals to calculation' function
* **NOT DONE** Change calc model to 'calculation name' and 'worksheet name'
* **DONE** Namespace rows by calulation and sheet 
  (Do I actually need the calcs abstraction at all then? It's doesn't add anything! maybe for comments?)
* **DONE** Model compilation should through if validations fail. Maybe with assertions?
* **DONE** Helper for `when-flag`, gloss on `(if flag x 0)`

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