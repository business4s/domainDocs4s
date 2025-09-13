package domaindocs4s.collector.fixtures.annotationargs.nonconstantname

import domaindocs4s.domainDoc

val nonConstName = "Non-constant name"

@domaindocs4s.domainDoc(name = nonConstName)
class Doc_NameNonConst