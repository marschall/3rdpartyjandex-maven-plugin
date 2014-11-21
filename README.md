3rd Party Jandex Generator
==========================

Why?
---
JavaEE 5 forces the application container to scan every class for certain annotations. With JavaEE 6 and 7 more annotations have to be searched. This can have quite a bad negative impact on deployment and start up times. To alleviate this JBoss offers a Maven plugin that builds an annotation index. Unfortunately many (most) 3rd party JARs come without such an index.

It is undesirable to modify 3rd party JARs. In addition whatever the solution it should work with skinny WARs.

How?
----
In the package phase after the final artifact is created this plugin adds an index to every JAR missing an index. The JAR is not modified and the index is places besides the JAR.

What does it work with?
-----------------------
EARs, WARs and RARs.

Does it modify 3rd party JARs?
-------------------------
No, only 3rd party RARs and WARs are modified.

Does it work with non-standard aritfacts (eg. SARs)?
-----------------------------------------------
No, JavaEE 6 eliminates the need for SARs.

Does it work with skinny WARs?
------------------------------
Yes
