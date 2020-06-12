# azure-throttling-exporter

Are you facing throttling issues in Azure? You are not alone.
This repository offers a prometheus exporter for Azure throttling metrics.
This is just a proof of concept. Use it at your own risk.

## How to run it

```
docker run --rm -it -p 8080:8080 \
    -e AZURE_AD_USER -e AZURE_PASSWORD -e AZURE_SUBSCRIPTION_ID \
    palmerabollo/azure-throttling-exporter:0.1.1
```

Open [localhost:8080](http://localhost:8080) to get the exposed metrics:
```
# HELP ms_ratelimit_remaining_resource_gauge metric_help
# TYPE ms_ratelimit_remaining_resource_gauge gauge
ms_ratelimit_remaining_resource_gauge{rate="Microsoft.Compute/HighCostGetVMScaleSet3Min"} 174
ms_ratelimit_remaining_resource_gauge{rate="Microsoft.Compute/HighCostGetVMScaleSet30Min"} 816
```

## TODO

- Support using a service principal.
- Include other resources.
- Clean the code and add unit tests.
