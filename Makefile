apt: deb
	sudo apt install --reinstall $(realpath target/runs_0.1_all.deb)

deb: bin
	sbt "debian:packageBin"

bin:
	sbt "universal:packageBin" 

