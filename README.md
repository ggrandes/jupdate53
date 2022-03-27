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

#### AWS-Credentials

See AWS documentation in [DefaultCredentialsProvider](https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/auth/credentials/DefaultCredentialsProvider.html) and [developer-guide](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/setup-additional.html).

If your plan to run webapp inside EC2, you can use EC2-role. This is a sample policy:

	{
	    "Version": "2012-10-17",
	    "Statement": [
	        {
	            "Effect": "Allow",
	            "Action": [
	                "route53:Get*",
	                "route53:List*",
	                "route53:TestDNSAnswer"
	            ],
	            "Resource": [
	                "*"
	            ]
	        },   
	        {
	            "Effect": "Allow",
	            "Action": "route53:ChangeResourceRecordSets",
	            "Resource": "arn:aws:route53:::hostedzone/YOUR-AWS-ZONE-ID"
	        }
	    ]
	}

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
