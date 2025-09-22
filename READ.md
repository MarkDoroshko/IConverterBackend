sudo apt install screen
sudo apt install openjdk-17-jdk


# Создать сессию с именем "iconverter-app"
screen -S iconverter-app

java -jar /var/www/fastuser/data/back/iconverter-0.0.1-SNAPSHOT.jar --server.port=8080


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
