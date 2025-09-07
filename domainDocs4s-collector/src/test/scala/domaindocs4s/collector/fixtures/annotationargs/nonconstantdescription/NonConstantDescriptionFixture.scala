package domaindocs4s.collector.fixtures.annotationargs.nonconstantdescription

import domaindocs4s.domainDoc

val nonConstDescription = "Non-constant description"

@domaindocs4s.domainDoc(description = nonConstDescription)
class Doc_DescNonConst
