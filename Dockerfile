FROM node:14.4.0-stretch-slim

EXPOSE 8080

RUN apt-get update && \
    apt-get install -y ca-certificates curl apt-transport-https lsb-release gnupg jq software-properties-common && \
    curl -L https://packages.microsoft.com/keys/microsoft.asc | gpg --dearmor | tee /etc/apt/trusted.gpg.d/microsoft.asc.gpg > /dev/null && \
    add-apt-repository 'deb [arch=amd64] https://packages.microsoft.com/repos/azure-cli/ stretch main' | tee /etc/apt/sources.list.d/azure-cli.list && \
    apt-get remove -y software-properties-common && \
    apt-get update && \
    apt-get install -y azure-cli=2.7.0-1~stretch && \
    apt-get remove -y software-properties-common && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /opt/app

COPY package.json package-lock.json* ./
RUN npm install --no-optional && npm cache clean --force
ENV PATH /opt/node_modules/.bin:$PATH

COPY index.js .
COPY token.sh .

CMD [ "node", "index" ]
