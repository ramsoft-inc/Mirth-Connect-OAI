# Mirth® Connect by NextGen Healthcare

1. [Useful Links](#useful-links)
2. [General Information](#general-information)
3. [Installation and Upgrade](#installation-and-upgrade)
4. [Starting Mirth Connect](#starting-mirth-connect)
5. [Running Mirth Connect in Java 9 or greater](#java9)
6. [Java Licensing](#java-licensing)
7. [License](#license)

------------

<a name="useful-links"></a>
## 1. Useful Links
- [Downloads](https://github.com/nextgenhealthcare/connect/releases) 
- [User Guide](https://docs.nextgen.com/)
- [Wiki](https://github.com/nextgenhealthcare/connect/wiki)
  - [FAQ](https://github.com/nextgenhealthcare/connect/wiki/Frequently-Asked-Questions)
  - [What's New in Mirth Connect](https://github.com/nextgenhealthcare/connect/wiki/Release-Notes)
- [Forums](https://forums.mirthproject.io/)
- [Slack Channel](https://mirthconnect.slack.com/) 
  - [Slack Registration](https://join.slack.com/t/mirthconnect/shared_invite/zt-1prqon9tg-UQ_~6AsV8IwdITTo3z1aoA)

------------

<a name="general-information"></a>
## 2. General Information
##### The NextGen Solutions Mission
NextGen Solutions help many of the nation&apos;s largest, most respected healthcare entities streamline their care-management processes to satisfy the demands of a regulatory, competitive healthcare industry. With Mirth Solutions, NextGen Healthcare&apos;s goal is to provide the healthcare community with a secure, efficient, cost-effective means of sharing health information. The natural product of this aim is a family of applications &mdash; which includes Mirth Connect &mdash; flexible enough to manage patient information, from small practices to large HIEs, so our clients and users can work confidently and effectively within the healthcare-delivery system.
##### About Mirth Connect
Like an interpreter who translates foreign languages into the one you understand, Mirth Connect translates message standards into the one your system understands. Whenever a &quot;foreign&quot; system sends you a message, Mirth Connect&apos;s integration capabilities expedite the following:
- Filtering &mdash; Mirth Connect reads message parameters and passes the message to or stops it on its way to the transformation stage.
- Transformation &mdash; Mirth Connect converts the incoming message standard to another standard (e.g., HL7 to XML).
- Extraction &mdash; Mirth Connect can &quot;pull&quot; data from and &quot;push&quot; data to a database.
- Routing &mdash; Mirth Connect makes sure messages arrive at their assigned destinations.

Users manage and develop channels (message pathways) using the interface known as the Administrator:
![Administrator screenshot](https://i.imgur.com/tnoAENw.png)

------------

<a name="installation-and-upgrade"></a>
## 3. Installation and Upgrade
Mirth Connect installers are available for individual operating systems (.exe for Windows, .rpm and .sh for Linux, and .dmg for Mac OS X). Pre-packaged distributions are also available for individual operating systems (ZIP for Windows, tar.gz for Linux, and tar.gz for Mac OS X). The installer allows you to automatically upgrade previous Mirth Connect installations (starting with version 1.5).

Mirth Connect installers also come with the option to install and start a service that will run in the background. You also have the option of installing and running the Mirth Connect Server Manager, which allows you to start and stop the service on some operating systems, change Mirth Connect properties and backend database settings, and view the server logs.

An optional Mirth Connect Command Line Interface can be installed, allowing you to connect to a running Mirth Connect Server using a command line. This tool is useful for performing or scripting server tasks without opening the Mirth Connect Administrator.

The Mirth Connect Administrator Launcher can also be installed, allowing you to manage connections to multiple Mirth Connect servers and configure options such as Java runtime, max heap size, and security protocols.

After the installation, the Mirth Connect directory layout will look as follows:

- /appdata/mirthdb: The embedded database (Do NOT delete if you specify Derby as your database). This will be created when the Mirth Connect Server is started. The path for appdata is defined by the dir.appdata property in mirth.properties.
- /cli-lib: Libraries for the Mirth Connect Command Line Interface (if installed)
- /client-lib: Libraries for the Mirth Connect Administrator
- /conf: Configuration files
- /custom-lib: Place your custom user libraries here to be used by the default library resource.
- /docs: This document and a copy of the Mirth Connect license
- /docs/javadocs: Generated javadocs for the installed version of Mirth Connect. These documents are also available when the server is running at `http://[server address]:8080/javadocs/` (i.e. `http://localhost:8080/javadocs/`).
- /extensions: Libraries and meta data for Plug-ins and Connectors
- /logs: Default location for logs generated by Mirth Connect and its sub-components
- /manager-lib: Libraries for the Mirth Connect Server Manager (if installed)
- /public_html: Directory exposed by the embedded web server
- /server-launcher-lib: Libraries in this directory will be loaded into the main Mirth Connect Server thread context classloader upon startup. This is required if you are using any custom log4j appender libraries.
- /server-lib: Mirth Connect server libraries
- /webapps: Directory exposed by the embedded web server to host webapps

------------

<a name="starting-mirth-connect"></a>
## 4. Starting Mirth Connect
Once Mirth Connect has been installed, there are several ways to connect to launch the Mirth Connect Administrator. On a Windows installation, there is a Mirth Connect Administrator item in the Start Menu which launches the application directly.

If the option is not available, you can connect to the Mirth Connect Administrator launch page which by default should be available at `http://[server address]:8080` (i.e. `http://localhost:8080`). It is recommended to use the Administrator Launcher to start the Administrator, which can be downloaded by clicking on the Download Administrator Launcher button. Clicking the Launch Mirth Connect Administrator button will download the Java Web Start file for your server. Opening the file with the Administrator Launcher connects you to the server, which will be listening on `https://[server address]:8443` (i.e. `https://localhost:8443`). 

If running a new installation, the default username and password for the login screen is admin and admin. This should be changed immediately for security purposes.

If you are launching the administrator for the first time, you will notice that the libraries for the Mirth Connect Administrator will be loaded. This feature allows you run the Administrator from any remote Mirth Connect server without having to download and install a separate client.

You may also notice a security warning when starting the administrator (dialog box depends on browser being used). This is because by default Mirth Connect creates a self-signed certificate for its web server. For now click Run to continue launching the administrator, but check out the User Guide for instructions on how to replace the certificate.

------------

<a name="java9"></a>
## 5. Running Mirth Connect in Java 9 or greater
In order to run Mirth Connect in Java 9 or greater, copy the options from `docs/mcservice-java9+.vmoptions` and append them to either mcserver.vmoptions or mcservice.vmoptions, depending on your deployment. Then restart Mirth Connect.

To run the Mirth Connect Command Line Interface, create a new file named mccommand.vmoptions in the Mirth Connect root directory. Copy all of the options from `docs/mcservice-java9+.vmoptions` into mccommand.vmoptions and save before launching the Command Line Interface.

------------

<a name="java-licensing"></a>
## 6. Java Licensing
In 2019, Oracle significantly changed licensing for official Oracle Java releases. You must now purchase a license in order to receive updates to the commercial version of Oracle Java. In response to this change, we officially added support for OpenJDK in Mirth Connect. OpenJDK receives free updates from Oracle for a period of 6 months following each release. While the Oracle OpenJDK distribution is recommended for use with Mirth Connect, we strive to support third-party OpenJDK distributions as well such as AdoptOpenJDK, Azul Zulu and Amazon Corretto. Third party distributions may receive extended release updates from their respective communities, but these are not guaranteed.

------------

<a name="license"></a>
## 7. License
Mirth Connect is released under the [Mozilla Public License version 2.0](https://www.mozilla.org/en-US/MPL/2.0/ "Mozilla Public License version 2.0"). You can find a copy of the license in `server/docs/LICENSE.txt`.

All licensing information regarding third-party libraries is located in the `server/docs/thirdparty` folder.
