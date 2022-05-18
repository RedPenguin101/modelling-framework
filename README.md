# Financial Modelling Framework

## Requirements
* Instant visual feedback on model changes. Either Excel or some other UI
* Rich format on feedback - formatting, etc.
* Circular dependency detection
* Daisy-chain detection
* Incremental build: allow placeholders

## Objects
### Row
* Types: placeholder, link, calculation

### Calculation
* Comprised of rows, constants
* Can be of type corkscrew?

### Others
* Page comprised of calculations
* Model comprised of Pages
* Model parameters - like the time sheet in Excel
* Inputs - base case, can be merged with scenarios 

## Todo
* trace-dependents / precedents
* **DONE** Presentation tables
* Excel interaction
* limit linkability to NS
* Check validity of functions in clacs
* Corkscrews
* Replace self references at model creation time?