# Todo
* Refactor: When generating table row, only pass the meta for that row to `row->table-row`
* Something about exports - either highlight or make declares?
* factor expression parsing/replacement to include following
    * `:row`
    * `:mean`
    * `:first`
    * `:window-mean 3`
    * `:look-back 2`
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
* Results Diff - colors on changed cells
* Save run outputs, display last x (like gridlines output sheet)
* Warnings on linked dependencies (links which are just other links)
* different types of col aggregation - sum, avg, etc.

### Secondary
* In place of `:total true`, Able to specify 'summarize' function, which will be applied to the results. e.g. mean, sum. This might have limited applicability
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
