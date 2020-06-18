# azure-throttling-exporter

Are you facing throttling issues in Azure? You are not alone.
This repository offers a prometheus exporter for Azure throttling metrics.

## How to run it

You need an Azure Service Principal and export the following environment variables to access Azure management endpoints:
- AZURE_CLIENT_ID
- AZURE_TENANT_ID
- AZURE_CLIENT_SECRET
- AZURE_SUBSCRIPTION_ID

```sh
docker run --rm -it -p 8080:8080 \
    -e AZURE_CLIENT_ID -e AZURE_TENANT_ID -e AZURE_CLIENT_SECRET -e AZURE_SUBSCRIPTION_ID \
    palmerabollo/azure-throttling-exporter:0.2.1
```

Open [localhost:8080](http://localhost:8080) to get the exposed metrics:
```
# HELP ms_ratelimit_remaining_resource_gauge Remaining resource reads before reaching the throttling threshold
# TYPE ms_ratelimit_remaining_resource_gauge gauge
ms_ratelimit_remaining_resource_gauge{rate="Microsoft.Compute/HighCostGetVMScaleSet3Min"} 174
ms_ratelimit_remaining_resource_gauge{rate="Microsoft.Compute/HighCostGetVMScaleSet30Min"} 816

# HELP ms_ratelimit_failures_total Number of failures trying to obtain Azure rate limits
# TYPE ms_ratelimit_failures_total counter
ms_ratelimit_failures_total 0.0
```

## How to create a Service Principal

```sh
az ad sp create-for-rbac --name myserviceprincipal --role contributor --years 1
```