package domaindocs4s.collector.fixtures.annotationargs.constant

import domaindocs4s.domainDoc

@domaindocs4s.domainDoc()
class Doc_NoArgs

@domaindocs4s.domainDoc(description = "Test description (literal).")
class Doc_DescLiteral

@domaindocs4s.domainDoc(name = "NameLiteral")
class Doc_NameLiteral

@domaindocs4s.domainDoc(description = "Test description (literal).", name = "BothLiterals")
class Doc_BothLiterals
