# Dockerfile do CLIENTE
FROM maven:3.9-eclipse-temurin-21

# Bibliotecas gráficas exigidas pelo JavaFX em runtime.
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        libgtk-3-0 \
        libx11-6 \
        libxext6 \
        libxrender1 \
        libxtst6 \
        libxi6 \
        libxxf86vm1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /vaijunto-client

# Mantém as dependências em uma camada reaproveitável enquanto o pom.xml não mudar.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src

# Falha durante o build se o código não compilar.
RUN mvn -B clean package -DskipTests

# Pode ser sobrescrito no docker run para apontar para o display do host.
ENV DISPLAY=:0

CMD ["mvn", "-B", "javafx:run"]