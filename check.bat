@echo off
docker ps > docker_status.txt
docker compose -f hazelcast-dcoker.yml logs --tail=50 > docker_logs.txt
echo DONE >> docker_status.txt
