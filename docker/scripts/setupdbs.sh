#!/bin/bash
set -e

echo "configuring databases..."

##########################################
# generic function to init containerized 
# databases need to be connected to docker
# daemon if running locally. if on travis
# there's no need to.
#
init_containerdb() {
  CONTAINERNAME=$1
  DBNAME=$(echo "$CONTAINERNAME" | cut -d_ -f1)
  docker cp init_$DBNAME.sh $CONTAINERNAME:/tmp/init_$DBNAME.sh
  docker exec $CONTAINERNAME /tmp/init_$DBNAME.sh
}

##########################################
# initialize locally running databases
# ie. non dockerized databaes. 
# this is here because travis initializes
# postgresql by default.
# so in the case we don't use a postgres
# container this method will initialize
# the locally running postgres db on
# travis
#
init_localdb() {
  if [[ $(ps -ef |grep {$1}) == "0" ]]
  then
    ./init_$1.sh
  fi

}

##########################################
# methods to intialize various databases
#

init_mongo() {
  # we only need to init mongo_read_only, the read only DB
  init_containerdb mongodb_read_only
}

init_postgresql() {
  # a restart of the container is needed here due to
  # http://askubuntu.com/questions/308054/how-to-create-s-pgsql-5432
  docker restart postgresql
  sleep 10
  init_containerdb postgresql
}

init_couchbase() {
  init_containerdb couchbase
}

init_marklogic() {
  ./init_marklogic.sh
}


##########################################
# attach our shell to docker
# this allows us to run docker commands
#
if [[ ${TRAVIS:-} ]]
then
  echo "in a travis environment, docker is in our path..."
else
  echo "local environment, looking for docker..."
  if [[ -x "$(command -v docker-machine)" ]]
    then
      echo "found docker-machine..."
      eval "$(docker-machine env default)"
    else
      echo "didn't find docker-machine, will assume docker is in your path..."
    fi
fi


##########################################
# parse arguments and run various
# database configuration scripts
#
CONTAINERS=$(docker ps --filter "name=" | awk '{if(NR>1) print $NF}')
for CONNECTOR in "$CONTAINERS"
  do
    if [[ $CONNECTOR == "mongodb_read_only" ]]; then init_mongo;      fi
    if [[ $CONNECTOR =~ "couchbase"         ]]; then init_couchbase;  fi
    if [[ $CONNECTOR =~ "marklogic"         ]]; then init_marklogic;  fi
    if [[ $CONNECTOR =~ "postgresql"        ]]; then init_postgresql; fi
done
