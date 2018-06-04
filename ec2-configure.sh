#!/bin/bash
wget --no-check-certificate --no-cookies --header "Cookie: oraclelicense=accept-securebackup-cookie" http://download.oracle.com/otn-pub/java/jdk/8u172-b11/a58eab1ec242421181065cdc37240b08/jdk-8u172-linux-x64.rpm
yum install -y jdk-8u172-linux-x64.rpm
rm jdk-8u172-linux-x64.rpm
export JAVA_HOME=$(readlink -f /usr/bin/java | sed "s:/bin/java::")

yum install -y git

curl -O https://download.clojure.org/install/linux-install-1.9.0.381.sh
chmod +x linux-install-1.9.0.381.sh
./linux-install-1.9.0.381.sh
rm linux-install-1.9.0.381.sh

wget https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein
chmod a+x lein
mv lein /usr/local/bin

cd /home/ec2-user
sudo -u ec2-user git clone https://github.com/things-nyc/mapthethings-server
cd mapthethings-server/
sudo -u ec2-user /usr/local/bin/lein deps
