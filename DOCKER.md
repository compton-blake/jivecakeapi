#### Building and running

```sh
cd path_to_your_project/docker
docker build -t jivecakeapi .

#Before you restart, you need to read DEVELOPER.md and adjust both `docker-compose.yml` and `example-settings.yml`
bash restart.sh
```