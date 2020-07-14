deb: 
	sbt "universal:packageBin" 
	sbt "debian:packageBin"
