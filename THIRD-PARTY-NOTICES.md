# Third-Party Notices

The release zips of intelliJ-birt (`intellij-birt-<version>.zip`, `intellij-birt-lite-<version>.zip`) contain, in the
`designer/plugins/` folder, **unmodified** OSGi bundles taken from an Eclipse Neon.3 installation with the Eclipse BIRT 4.8
designer (BIRT build v201806261756). They are redistributed under their own licenses, not under the MIT License of this repository.

- The complete list of included bundles (name, version, file) is `designer/bundles.txt` inside the zip
  (sources: [`render/bundles.txt`](render/bundles.txt) for both editions and [`render/bundles-full.txt`](render/bundles-full.txt) for the Full edition only).
- Each bundle contains its own `about.html` (and, where applicable, `about_files/` with license and notice files) stating its
  license and where its source code can be obtained. Those files are kept as-is.
- No JDBC drivers are included.
- `designer/java-modern.profile` (also `render/java-modern.profile` in the repository) is a modified copy of `JavaSE-1.8.profile` from Eclipse Equinox (`org.eclipse.osgi`) and is licensed under the Eclipse Public License 1.0, as stated in its header.

## Licenses of the included bundles

| Component | License | Notes |
|---|---|---|
| Eclipse BIRT 4.8 (report designer, engine, model, data, charts, viewer components) | Eclipse Public License 1.0 | Also the fragments `org.apache.batik.ext.awt.extension` and `org.w3c.dom.svg.extension`, which are part of the BIRT 4.8 build |
| Eclipse Platform (Equinox/OSGi, SWT, JFace, Workbench, Text, Forms, Help base, Ant integration, PDE parts) | Eclipse Public License 1.0 | Some bundles include parts under the Apache License 2.0 (e.g. Equinox, Ant integration) or the Mozilla Public License 1.1 (SWT), as stated in their `about.html` |
| Eclipse GEF / Draw2d | Eclipse Public License 1.0 | |
| Eclipse Data Tools Platform (ODA, connectivity, SQL tools, Flat File, XML, Web Services data sources) | Eclipse Public License 1.0 | |
| Apache Batik, Xerces, XML Commons, Commons (Codec, Logging, JXPath, Discovery), log4j 1.2, Lucene, Ant 1.9, Axis 1.4, POI 3.9, Derby 10.11, W3C DOM/SAC APIs | Apache License 2.0 (one bundle Apache License 1.1) | `NOTICE` files are kept in each bundle's `about_files/` |
| iText 2.1.7 (`com.lowagie.text`) | Mozilla Public License 1.1 (some parts Apache License 2.0) | Unmodified |
| Mozilla Rhino (`org.mozilla.javascript`) | Mozilla Public License 2.0 | Unmodified |
| `javax.activation`, `javax.mail` | Apache License 2.0 | |
| `javax.annotation`, `javax.servlet` | Common Development and Distribution License 1.0 | Unmodified |
| Other `javax.*` APIs (xml, wsdl, xml.rpc, xml.soap, inject, …) | As stated in each bundle (EPL 1.0 / Apache License 2.0) | |
| ICU4J (`com.ibm.icu`) | ICU License (MIT-style) | |
| SLF4J | MIT License | |
| Hamcrest | BSD License | |
| SAT4J | Eclipse Public License 1.0 (as distributed by Eclipse) | |
| MongoDB Java Driver 3.2 (`org.eclipse.orbit.mongodb`, Full edition only) | Apache License 2.0 | |
| Hector, LPG runtime | Eclipse Public License 1.0, as stated in each bundle | |

The full text of the Eclipse Public License 1.0 is in [`licenses/EPL-1.0.html`](licenses/EPL-1.0.html).

## Source code

Source code for the EPL-licensed bundles is available from the Eclipse Foundation projects:

- Eclipse BIRT: https://github.com/eclipse-birt/birt (4.8 release) — project page https://projects.eclipse.org/projects/technology.birt
- Eclipse Platform: https://projects.eclipse.org/projects/eclipse.platform
- Eclipse GEF: https://projects.eclipse.org/projects/tools.gef
- Eclipse Data Tools Platform: https://projects.eclipse.org/projects/datatools
- Third-party libraries repackaged by Eclipse Orbit: https://projects.eclipse.org/projects/tools.orbit

Each bundle's `about.html` gives the exact origin of its contents.

## Disclaimer

These components are provided by their respective authors "AS IS", without warranties or conditions of any kind. Any terms
offered by intelliJ-birt that differ from a component's license are offered by intelliJ-birt alone and not by the original
contributors. Eclipse and BIRT are trademarks of the Eclipse Foundation; intelliJ-birt is not affiliated with the Eclipse Foundation.
