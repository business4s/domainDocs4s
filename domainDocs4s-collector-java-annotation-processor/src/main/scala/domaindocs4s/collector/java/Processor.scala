package domaindocs4s.collector.java

import domaindocs4s.java.domainDoc

import java.util
import javax.annotation.processing.{AbstractProcessor, RoundEnvironment, SupportedAnnotationTypes, SupportedSourceVersion}
import javax.lang.model.SourceVersion
import javax.lang.model.element.{Element, ElementKind, TypeElement}
import javax.tools.Diagnostic
import scala.jdk.CollectionConverters.*

@SupportedAnnotationTypes(Array("domaindocs4s.java.domainDoc"))
@SupportedSourceVersion(SourceVersion.RELEASE_17)
class Processor extends AbstractProcessor {

  override def process(annotations: util.Set[_ <: TypeElement], roundEnv: RoundEnvironment): Boolean = {

    roundEnv.getElementsAnnotatedWith(classOf[domainDoc]).asScala.foreach { element =>
      if (element.getKind == ElementKind.CLASS) {
        // Extract the class and the annotation value
        val typeElement = element.asInstanceOf[TypeElement]
        val annotation = element.getAnnotation(classOf[domainDoc])

        // Output the class name and annotation value (you can do any processing here)
        val className = typeElement.getQualifiedName.toString
        val annotationValue = annotation.description()
        processingEnv.getMessager.printMessage(Diagnostic.Kind.NOTE,
          s"Class: $className annotated with @MyAnnotation value: $annotationValue")
      }
    }
    true // Indicate that the annotations are processed
  }
}
