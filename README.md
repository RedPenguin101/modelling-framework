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
* **DONE** trace-dependents / precedents
* **DONE** Presentation tables
* **DONE** Corkscrews
* **DONE** Placeholders
* limit linkability to NS?
* Check validity of functions in calcs
* Replace self references at model creation time?
* Placeholder indication
* Excel interaction
* Checks on model start
* Better printing
* Performance optimization
  * Change detection and update based on deps?
  * better table lookup?
  * Earlier 'compilation'?

## Jottings on V3
* Imports seem pretty useless
* One row per calc? Is that really what we want?
* Categorization is clunky
* keep forgetting to add calcs
* formatting for table