apt: deb
	sudo apt install --reinstall $(realpath target/runs_0.1_all.deb)

deb: 
	sbt "universal:packageBin" 
	sbt "debian:packageBin"
