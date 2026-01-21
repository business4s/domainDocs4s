package domaindocs4s.collector.fixtures.annotationargs

import domaindocs4s.domainDoc

@domainDoc()
class Doc_NoArgs

@domainDoc(description = "Test description (literal).")
class Doc_DescLiteral

@domainDoc(name = "NameLiteral")
class Doc_NameLiteral

@domainDoc(description = "Test description (literal).", name = "BothLiterals")
class Doc_BothLiterals
