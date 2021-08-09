# uk-deprivation

> Deprivation indices in the UK.

This provides code and data for UK indices of deprivation.

Deprivation is an important determinant of outcomes in health and care.

This repository provides a simple microservice, embeddable library and simple 
command-line tools to allow other software to make use of deprivation indices in the UK such as in operational electronic health record systems and analytics. 

You can use the NHS Postcode Database to lookup a UK postal code and find out
the LSOA code for that area. My microservice and library [nhspd](https://github.com/wardle/nhspd)
provides a simple lightweight wrapper around that data product. 

I combine medical records with [nhspd](https://github.com/wardle/nhspd) and the
data made available via this repository so I can include deprivation as a 
factor in analysis using a graph-like API as part of my PatientCare EPR. 

# Background

We know socio-economic deprivation has a significant effect on health outcomes.

Individual indices for a geographic region, such as England or for Wales, are available. If you are studying a
population from a single region, you can use the index for that region. Indices are available are available for
a range of domains, and usually include a composite index providing a view across multiple domains.

However, if your study population is from multiple areas, you cannot use disparate indices and assume rank 1 in, for example
England, is the same as rank 1 in Wales. Likewise, you cannot assume the upper quartile in England is equivalent
to the upper quartile in Wales.

The work by Abel, Payne and Barclay provides a way to harmonise across England and Wales, but there is also indices
based on income and employment, published for England and Wales. Abel, Payne and Barclay published a composite UK
index for 2016, and [MySociety have used the same approach](https://github.com/mysociety/composite_uk_imd) for 2020.

In practical terms, whichever index used, most studies will not want to use ranks directly, but instead use quartiles
or quintiles.


# Deprivation indices 


| Name                         | Supported?  |
| ---------------------------- | -------------------- |
| [MySociety adjusted UK indices of deprivation 2020](https://github.com/mysociety/composite_uk_imd) using the same approach as per Abel, Payne and Barclay but for 2020 | Yes |
| [Welsh Index of Deprivation](https://gov.wales/welsh-index-multiple-deprivation) | Pending |
| [English Index of Deprivation](https://www.gov.uk/government/collections/english-indices-of-deprivation) | Pending |
| Composite UK index, 2016 using the resources generated by [Gary Abel, Rupert Payne, Matt Barclay (2016): UK Deprivation Indices.](https://doi.org/10.5523/bris.1ef3q32gybk001v77c1ifmty7x) | Pending |
| [Indices of Deprivation for income and employment domains combined for England and Wales (since 2019)](https://www.gov.uk/government/statistics/indices-of-deprivation-2019-income-and-employment-domains-combined-for-england-and-wales) | Pending |

Unfortunately, the "Welsh Index of Multiple Deprivation" and the "English Index of Multiple Deprivation" are not easily
comparable. Each generates a rank based on a geographical region called an LSOA (Lower Layer Super Output Area) but the
top rank in Wales is not equivalent to the top rank in England. 

# What is this repository?

This repository provides a way to automatically download and make those data available in computing systems. 
It is designed to be composable with other data and computing services in a graph-like API. 
In essence, it provides a simple way to lookup a deprivation index based on LSOA. 
A LSOA is a small defined geographical area of the UK containing about 1500 people designed to help report small
area statistics.
You can use [nhspd](https://github.com/wardle/nhspd) to map from a UK postal code to an LSOA.

# Getting started

1. Download and [install clojure](https://clojure.org/guides/getting_started).

e.g. on mac os x with homebrew:

```shell
brew install clojure/tools/clojure
```

2. Clone this repository

```shell
git clone https://github.com/wardle/uk-deprivation
cd uk-deprivation
```

3. List available datasets

```shell
clj -X:list
```

Result:

```
|                                                         :name |                         :id |
|---------------------------------------------------------------+-----------------------------|
| UK composite index of multiple deprivation, 2020 (MySociety). | uk-composite-imd-2020-mysoc |
```

4. Get information about a dataset

Specify a key value pair, :dataset and the 'id' of the dataset in which you are interested.

e.g.
```shell
clj -X:info :dataset uk-composite-imd-2020-mysoc
```

Result:

```
UK composite index of multiple deprivation, 2020 (MySociety)
------------------------------------------------------------
A composite UK score for deprivation indices for 2020 - based on England
with adjusted scores for the other nations as per Abel, Payne and Barclay but
calculated by Alex Parsons on behalf of MySociety.
```

5. Install a dataset into a file-based database.

```shell
clj -X:install :db depriv.db :dataset uk-composite-imd-2020-mysoc
```

This will take a few seconds only.

6. List your installed datasets:

```shell
clj -X:installed :db depriv.db
```
