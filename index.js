const http = require("http");
const https = require("https");
const util = require("util");
const exec = util.promisify(require('child_process').exec);
const logger = require('logops');

if (!process.env.AZURE_SUBSCRIPTION_ID || !process.env.AZURE_AD_USER || !process.env.AZURE_PASSWORD) {
  logger.error("Missing environment variables");
  process.exit(1);
}

const prometheus = require("prom-client");

const gauge = new prometheus.Gauge({
  name: "ms_ratelimit_remaining_resource_gauge",
  help: "Remaining resource reads before reaching the throttling threshold",
  labelNames: ["rate"]
});

const requestOptions = {
  host: "management.azure.com",
  path: `/subscriptions/${process.env.AZURE_SUBSCRIPTION_ID}/providers/Microsoft.Compute/virtualMachineScaleSets?api-version=2019-12-01`,
  timeout: 4000
};

function delay(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

async function loop() {
  let expiresOn;
  let token;

  while (true) {
    if (!expiresOn || expiresOn < Date.now()) {
      logger.info(`Request a new azure token`);
      const { stdout, _ } = await exec("sh token.sh");
      token = JSON.parse(stdout);

      if (!token.accessToken) {
        logger.error(`Not able to get access token`);
        process.exit(2);
      }

      expiresOn = new Date(token.expiresOn).getTime() - 5 * 60 * 1000;
    }

    const req = https.request(requestOptions, async (res) => {
      if (res.statusCode < 300) {
        // Header example:
        // "x-ms-ratelimit-remaining-resource": "Microsoft.Compute/HighCostGetVMScaleSet3Min;170,Microsoft.Compute/HighCostGetVMScaleSet30Min;852"
        const header = res.headers["x-ms-ratelimit-remaining-resource"];
        logger.info(`Health probe ok ${res.statusCode}: ${header}`);

        if (header) {
          header.split(",").forEach(resource => {
            const [rate, value] = resource.split(";");
            gauge.set({"rate": rate}, Number(value));
          });
        }
      } else {
        logger.error(`Unexpected status code ${res.statusCode}: ${res.statusMessage}`);
      }
    });
    req.setHeader("Authorization", `Bearer ${token.accessToken}`);
    req.end();

    req.on("error", (e) => {
      logger.error(`Problem with request: ${e.message}`);
    });

    await delay(Number(process.env.REFRESH_INTERVAL || 60000));
  }
};

(async () => {
  loop();

  http.createServer((req, res) => {
    res.writeHead(200, { "Content-Type": prometheus.register.contentType })
       .end(prometheus.register.metrics());
  }).listen(process.env.PORT || 8080);
})().catch(e => {
  logger.error(`Something went wrong: ${e.message}`);
});

function handle(signal) {
  logger.info(`Received ${signal}`);
  process.exit(0);
}

process.on('SIGINT', handle);
process.on('SIGTERM', handle);