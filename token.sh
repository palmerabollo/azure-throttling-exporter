#!/bin/bash

az login -u "$AZURE_AD_USER" -p "$AZURE_PASSWORD" > /dev/null
az account get-access-token
