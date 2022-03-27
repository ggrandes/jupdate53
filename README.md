# jUpdate53

Simple DynDNS service for update AWS Route53 backend. Open Source Java project under Apache License v2.0

### Current Stable Version is [1.0.0](https://github.com/ggrandes/jupdate53/releases)

---

## DOC

#### Configuration

    # SystemProperty / Default value
    org.javastack.jupdate53.config.path=/etc/

###### Whitelist config file: org.javastack.jupdate53.whitelist.properties

    # whitelist
    # fqdn=zoneid
    name1.acme.org=AWSZONEID...A
    name2.acme.com=AWSZONEID...B


## API Usage

The API is very simple:

    # Method: POST
    # Path: /update
    # Content-Type: application/x-www-form-urlencoded
    # Parameters: 
    #   - zoneid=${AWSZONEID}
    #   - fqdn=${fully-qualified-domain-name}
    # Optionals:
    #   - ttl=${SECONDS}
    #   - ip=${IPv4}
    # Example: curl -i -d "zoneid=AWSZONEID...A&ttl=3&fqdn=name1.acme.org" ${BASE_URL}/update

Return something like this:

    HTTP/1.1 200 OK
    Cache-control: private
    Content-Type: application/json;charset=ISO-8859-1
    Content-Length: 34
    
    { "status": "PENDING:127.1.1.42" }

---

## MISC
Current hardcoded values:

* Default config path: /etc/
* Default whitelist config name: org.javastack.jupdate53.whitelist.properties
* Default update "too-fast" (seconds): 30
* Default wait-insync-route53 (seconds): 60 `not used`

---
Inspired in [DynV6](https://dynv6.com/) and [No-IP](https://www.noip.com/), this code is Java-minimalistic version.
