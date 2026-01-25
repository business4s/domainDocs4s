package domaindocs4s.custom

// start_usage
import domaindocs4s.domain._
import domaindocs4s.custom._

object CustomDomain {

  @domainDoc()
  class ClassA {

    @domainDoc(relation = aggregation)
    class ClassB

    @domainDoc(relation = association)
    class ClassC {

      @domainDoc(relation = dependency)
      class ClassD

    }

  }

}
// end_usage
