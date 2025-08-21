sudo apt install screen
sudo apt install openjdk-17-jdk


# Создать сессию с именем "iconverter-app"
screen -S iconverter-app

java -jar /media/doroshkoav/m2_sdd/MySiteConverter/back/target/iconverter-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
или с явным указанием пути к java
/usr/lib/jvm/java-17-openjdk-amd64/bin/java -jar /media/doroshkoav/m2_sdd/MySiteConverter/back/target/iconverter-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod



# После запуска приложения нажмите:
Ctrl + A, затем D
# Это detach - сессия продолжит работу в фоне

# Посмотреть все активные сессии:
screen -ls



#Остановка приложения
# Завершить конкретную screen сессию
screen -S iconverter-app -X quit

# Подключиться к сессии
screen -r iconverter-app

# Остановить приложение (Ctrl+C)
# Или ввести exit

# Выйти из screen
exit
