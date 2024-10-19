# DomainDocs4s

This repository contains experiments leading to the DomainDocs4s design. 
See the [article](https://medium.com/@w.pitula/in-validating-library-design-domaindocs4s-ddd-reflection-classpath-scanning-9d86449859cf) for the context.  


## Collecting meta

<table>
  <tr>
    <td rowspan="2"></td>
    <td colspan="4">Can be attached to</td>
    <td colspan="3">Can be extracted with</td>
  </tr>
  <tr>
    <th>Packages</th>
    <th>Classes, traits, objects</th>
    <th>Methods, fields</th>
    <th>Type declarations</th>
    <th>Scala compile-time reflection</th>
    <th>Java reflection</th>
    <th>Scala runtime reflection</th>
  </tr>
  <tr>
    <th>Scala Annotation</th>
    <td>❌ <sup>[1]</sup></td>
    <td>✅</td>
    <td>✅</td>
    <td>✅</td>
    <td>✅</td>
    <td>❌</td>
    <td rowspan="4">❌ <sup>[2]</sup></td>
  </tr>
  <tr>
    <th>Java Annotation</th>
    <td>✅ <sup>[3]</sup></td>
    <td>✅</td>
    <td>✅</td>
    <td>❌</td>
    <td>✅</td>
    <td>✅</td>
  </tr>
  <tr>
    <th>Trait</th>
    <td>✅</td>
    <td>✅</td>
    <td>❌</td>
    <td>~ <sup>[4]</sup></td>
    <td>✅</td>
    <td>✅</td>
  </tr>
  <tr>
    <th>ScalaDoc</th>
    <td>✅</td>
    <td>✅</td>
    <td>✅</td>
    <td>✅</td>
    <td>✅</td>
    <td>❌</td>
  </tr>
</table>


