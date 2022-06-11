# Todo
* Something about exports - either highlight or make declares?
* Expand corkscrews - line item for each increase/decrease, not sum
* Goal seek following 'IRR For Coinvestor' in Gridlines
* Circular dependency - find and display
* Improve checks, show which ones.
* Display errors (bad refs) in output, not REPL. Goal should be no repl required for simple things.
* Inputs, base cases, scenarios
* Warnings on linked dependencies (links which are just other links)
* Frontend select sheet?

### Secondary
* Results Diff - colors on changed cells
* Check on rows, so they can be highlighted?
* Different color for local link as opposed to import?
* factor expression parsing/replacement to include following
    * `:row`
    * `:mean`
    * `:first`
    * `:window-mean 3`
    * `:look-back 2`
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
