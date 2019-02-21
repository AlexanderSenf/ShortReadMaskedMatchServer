# ShortReadMaskedMatchServer

Align a short read against an indexed reference genome and process alignment positions against a mask

EXPERIMENTAL ALPHA VERSION

Currently requires Java 8 to build the project and Docker image. Pre-build images are available, built on Apline and Java 8.

# Setup

This project depends on BWA (https://github.com/lh3/bwa, to generate the index files) and JBWA (https://github.com/lindenb/jbwa, to provide Java JNI mappings for BWA).
This repository comes with pre-compiled binaries for Linux.

`cd ShortReadMaskedMatchServer`

The JBWA project provides a library named `jbwa.jar`. This file is provided pre-compiled in the /lib directory (the Maven version does not work for me)
`mvn deploy:deploy-file -Durl=file:///$HOME/.m2/repository -Dfile=lib/jbwa.jar -DgroupId=com.github.lindenb -DartifactId=jbwa -Dpackaging=jar -Dversion=1.0.1`

The JBWA project provides a file named `libbwajni.so` which is required to run the server. This file must be in the in the `java.library.path`, or it must be specified via `-Djava.library.path={path}` upon starting the server (see example below).
I like to copy that file into a new directory `/usr/lib/jni` then the `-Djava.library.path` parameter is not necessary.

The server is then compiled using `mvn package` and be placed in the `target/` directory. A precompiled executable jar file is already in that directory, which should work on all target platforms.

The maven command automatically generates a Docker image. This requires Docker to be installed; the Docker user should be added (`sudo usermod -aG docker {username}`, then restart), otherwise compile with `sudo mvn package`.
The docker image is named `uk.ac.ebi/bwaserver`.

# Start Directly

The compiled server can be started:
`java -Djava.library.path={path to libbwajni.so} -jar bwaserver-1.0-SNAPSHOT.jar -l {path to index}`

This will start the server on port 9221 and create a mask file in the current directory.

# Start Docker Image

The Docker image generated needs to map external directories for the mask file as well as the index files. It also needs to expose a port to enable access to the server.

The option for mapping diectories is: `-v /host/directory:/container/directory` <br />
The option for mapping ports is `-p external_port:container_port`

One example start commant line would be:

`docker run -v /home/asenf/Documents/ecoli/:/usr/index/ -v /home/asenf/Documents/mask/:/usr/mask/ -p 9221:9221 uk.ac.ebi/bwaserver -v -m /usr/mask/mask -l /usr/index/29.AP009048.sequence.fasta`

In this example, the external index and mask directories are simply mapped to `/usr/mask` and `/usr/index`.
The options specified after the image name are then passed to the startup of the server insie of the Docker container, using paths relative to the container.
In this example the BWA index used by bwa inside the Docker container is on system path: `/home/asenf/Documents/ecoli/29.AP009048.sequence.fasta`.

# Docker Repository

This project is also available is a complete Docker image at https://hub.docker.com/r/alexandersenf/bwaserver. To run the image from here:

`docker pull alexandersenf/bwaserver:firsttry`

The name of the image is `alexandersenf/bwaserver:firsttry` (at the moment). So to start this image: 

`docker run -v /home/asenf/Documents/ecoli/:/usr/index/ -v /home/asenf/Documents/mask/:/usr/mask/ -p 9221:9221 alexandersenf/bwaserver -v -m /usr/mask/mask -l /usr/index/29.AP009048.sequence.fasta`

# Options

There are 5 possible options: <br />
'-p'  port (default 9221) <br />
'-c'  cores (default 4*available system cores) <br />
'-l'  path&prefix to genome index files <br />
'-m'  path to mask file(s) (default .) <br />
'-v'  verbose mode 

# Queries

The only two URLs currently availble: 

(1) '/v1/proc' ("proceed - yes or no"). The sequence to me mapped is provided a query parameter 'seq'. For example:

`curl http://localhost:9221/v1/proc?seq=CTCTATTATTAATACTTCTTTTGAAGCTGCAGTTGTTGCTTCTACTTCAACATTAGAATTAATGTGTA`

This curretly returns a JSON response: (for example) `{"Pos":[120462618],"MaskValForward":[0],"MaskValReverse":[0]}`
This lists the alignment position, and the mask value at that position.

(2) '/v1/mask' (just for testing):

`curl http://localhost:9221/v1/mask?pos=100`

This returns the two 4-byte values of the mask at the specified position. Useful for testing if the server reads the mask file correctly.

# Issues

(1) The preferred Docker base image `openjdk:jre-alpine` procudes a Docker image of size 90MB, but causes an error when loading the BWI index file. The remeby was to switch to the standard `openjdk:8` image, but now the Docker image is 631MB.

(2) Maven build doesn't work on JDK9 at the moment.

