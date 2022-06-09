# Dev Diary
## Todo
### MVP
* **DONE** Blue text for imports
* Fix charting to handle negative numbers
* Totalled calc placeholder yellow 
* Expand corkscrews - line item for each increase/decrease, not sum
* Different color for local link as opposed to import?
* Select outputs to show
* Goal seek following 'IRR For Coinvestor' in Gridlines
* Change outputs to calculate on results, not display
* Circular dependency - find and display
* Improve checks, show which ones.
* Check on rows, so they can be highlighted?
* Display errors (bad refs) in output, not REPL 
* Inputs, base cases, scenarios
* Output comparison across scenarios
* Save run outputs, display last x (like gridlines output sheet)
* Results Diff - colors on changed cells
* Warnings on linked dependencies (links which are just other links)
* different types of col aggregation - sum, avg, etc.

### Other
* Only-dependencies recalculation? Need to recompile model function
* Isolate and show circular dependencies
* Check on print table where there are no rows, or you got the sheet name wrong
* Model report: 
  * number of rows
  * number of evaluations per period
  * Unused rows
  * Placeholders that need to be replaced
* flag optimizations: when you've calculated the flags, you can store them in a set, then if an expression depends on the flag you can just look it up and avoid evaluating if the flag isn't true
* Circularity helpers, but only when it comes up
* Some sort of limited "sheet" recalculation. Everything in the ns is recalculated, but any external references are looked up in a cache
* Multiple headers

## 2nd June - checks
Something like 
```clojure
(check! "balance-sheet-balances" '(= [:balance-sheet/total-assets] [:balance-sheet/total-liabilities]))
```

Behind the scenes, this is just another model row

On running model, in addition to printing the table it will output a summary

```
ALL CHECKS PASSED

--or--

WARNING: CHECKS FAILED
- balance-sheet-balances: period-end-date 2020-12-31, 2021-12,...
- other checks
```

Alternative: _Any_ row can be flagged as a check in the metadata. So like

```clojure
(f/totalled-calculation!
 "balance-sheet.assets" :total-assets
 :cash             [:cashflows.retained-cash/end]
 :receivables      [:receivables/end])

(f/totalled-calculation!
 "balance-sheet.liabilities" :total-liabilities
 :equity            [:placeholder 0]
 :retained-earnings [:income.retained-earnings/end])

(f/calculation!
 "balance-sheet"
 :check   '(= [:balance-sheet.assets/total-assets] 
              [:balance-sheet.liabilities/total-liabilities]))

(f/meta-data! {:balance-sheet/check {:units :bool :check true}})
```

This could clutter up the model a bit. I think I prefer keeping it separate. You could add more spec-like validation, like

```clojure
(check! :volume-always-positive '(pos? [:volume/end]))
```

## 1st June
It's time to work on a UI for displaying results. There should be 2 'modes' which the user can select from: build mode and run mode. The emphasis for build mode should be on the results, and for run mode the outputs and different scenarios.

The UI should refresh whenever the model is re-run.

The UI is not two way. That is, the user can't change the model from the UI. That gets done from the editor.

The following are the important elements

### Results
This should mirror the spreadsheets. The user should be able to select the Sheet they want to display, as well as the number of periods.

The measure selection should be hierarchical. That is, if the hierarchy looks like:

```
Debt
  RCF
    RCF Balance
  Senior Loan
    Senior Loan Balance
Financials
  Income
  Balance Sheet
  Cashflows
```

Users should be able to select any level and have all rows included in the output.

Calculations should be grouped under headings.

The user should have the option to include imports/references to other calcs as a separate table.

Rows with placeholders should be highlighted in yellow.

### Outputs, Checks and Warnings
Outputs should be displayed along with deltas from the previous run.

If there are variations on the inputs, the outputs from each of the variations should be displayed along with the base case.

### Model stuff
A graph of dependencies for the calculation that is being worked on should be displayed.

The user should be able to click on a row and have a dependency graph for that row become visible. Hovering over the row should display the formula for that row.

Model summary - number of calculations, rows, unused rows.

If the model fails to compile, The UI should display the problem - e.g. for circular dependencies it should display the dependency graph, for bad references it should display the bad references.

## 29th May
Currently I'm working though the Power Up scenario from FMWK. The concept is that you have 10 contracts which you can either take or not take, and you need to be able to model the revenues, costs and cashflows from the contracts.

So a contract might have a revenue of X and a cost of Y. It has a completion schedule which determines in a given work how much of the contract you complete (e.g. 25%). And Some stuff about billing in advance, holdbacks, payment terms etc.

So you might model it something like this.

```clojure
{:materials      123
 :salaries       123
 :OVH-and-profit 123
 :total          369
 :completion     [0 0 0 0 0 0 0.1 0.2 0.25 0.25 0.2]}
```

Your revenue would be the total * completion for the month. Your cashflow would be 80% of that, and 10% would go to advance release and holdback accrual.

Pretty simple, but how does that fit into what I've built? I can think of two ways to solve this:

1. Precalculate everything outside of the model and allow users to create input vectors which are just put straight into the results set.
2. Allow users to create collections as inputs.

The first might look like this:

```clojure
(def revenue-row
  (for [c (:completion contract)
    (* c (:total contract))]))

(def inputs
  {:contract-revenue [:row revenue-row]})
```

This is probably simpler, but the problem comes when the row itself has dependencies (i.e. can't be precalculated.) This is what I'm going to try first.

## 26th May
* **DONE** Units and metadata
* **DONE** Printing of Percent
* **DONE** Totals

## 25th May
* **DONE** Better printing
  * **DONE** Better selection of sheets, rather than calcs (i.e. select "fs" to select everything in the FS, including fs.assets)
  * **DONE** change print-calcs so it prints in a single table
  * **DONE** (badly) Do something about order
* **DONE** Compile model to function on arrays to increase performance?

## 23rd May 2022
The model process
* Define maps of (qualified) "row-names" to formula (which themselves reference rows)
* The definitions must retain order, but this is only 'display order' for users.
* Generate the dependency graph of the rows, do a top sort on it. This is the second, much more important order, the calculation order
* To generate a "period", the runner iterate through each row in the calculation order, generating the value for that period.
* Rinse and repeat, for as many periods as required. 


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
