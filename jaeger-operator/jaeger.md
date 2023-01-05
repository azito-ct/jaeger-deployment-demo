# Jaeger deployment

## Architecture

The target design is comprised of a Jaeger backend in the `search-tools` namespace, used to collect spans for application deployed in the `search` namespace.

In order to achieve this we will use the Jaeger Operator to:
- manage the Jaeger backend instances (single instance: `jaeger-search`)
- watch for new containers in the `search` namespace and inject an agent reporting to the appropriate instance (i.e. `jaeger-search`)

The more conservative deployment strategy for the Jaeger Operator, limiting the scope of the required RBAC permissions, would be to:
- restrict its monitoring for Jaeger resources to the `search-tools` namespace
- rescrict its monitoring of deployment for agent injection to the `search` namespace

Unfortunately due to a series of open issue this strategy is currently not feasable:
- https://github.com/jaegertracing/jaeger-operator/issues/929
- https://github.com/jaegertracing/jaeger-operator/issues/1431
- https://github.com/jaegertracing/helm-charts/issues/241

Due to these issue the current prototype deploys the Jaeger Operator with cluster-wide monitoring.

## Operator
The [Jaeger Operator](https://www.jaegertracing.io/docs/1.40/operator/) can be installed via the dedicated 
[Helm chart](https://github.com/jaegertracing/helm-charts/tree/main/charts/jaeger-operator).

As a pre-requisite the Jaeger Operator requires [cert-manager](https://cert-manager.io/), which can be installed through
the dedicated [Helm chart](https://cert-manager.io/docs/installation/helm/).

Once the pre-requisite are installed you can deploy the operator using the following command:
```sh
helm install jaeger-operator \
             jaegertracing/jaeger-operator \
             -n search-tools \
             --set image.tag=1.40.0 \
             --set rbac.clusterRole=true
```

## Jaeger instance

In order to create a Jaeger backend instance create a configuration for the custom resource:

```yaml
apiVersion: jaegertracing.io/v1
kind: Jaeger
metadata:
  name: jaeger-search
spec:
  strategy: production
  storage:
    #type: elasticsearch
    type: memory
    options:
      es:
        #server-urls: http://elasticsearch:9200
```

and apply it in the namespace you want to host the Jaeger backend (i.e. `search-tools`): 
`kubectl create  -f jaeger-operator-instance.yaml -n search-tools`

You should see the following entries in the operator logs:
```
1.6722319717162688e+09  INFO    Reconciling Jaeger      {"namespace": "search-tools", "instance": "jaeger-search", "execution": 1672231971.716265}
1.6722319717277467e+09  INFO    jaeger-resource default {"name": "jaeger-search"}
1.6722319717291927e+09  INFO    jaeger-resource validate update {"name": "jaeger-search"}
1.6722319717317955e+09  INFO    configured this operator as the owner of the CR {"instance": "jaeger-search", "namespace": "search-tools", "execution": 1672231971.716265, "operator-identity": "search-tools.jaeger-operator"}
1.6722319717318773e+09  INFO    Reconciling Jaeger      {"namespace": "search-tools", "instance": "jaeger-search", "execution": 1672231971.7318757}
```

And a new deployment `jaeger-search` should have been created in the `search-tools` namespace.

## Injecting the agent into a deployment

In order for the Jaeger Operator to inject the agent into your deployment you need to add the annotation `"sidecar.jaegertracing.io/inject": "true"` inside the metadata:

```
apiVersion: apps/v1
kind: Deployment
metadata:
  name: <my-app>
  labels:
    app: <my-app>
  annotations:
    "sidecar.jaegertracing.io/inject": "true"
```

As soon as you deploy your application you should see the following lines in the log of the Jaeger Operator:

```
1.6722324337106442e+09  INFO    verify deployment       {"namespace": "search"}
1.6722324337108047e+09  INFO    annotation present on deployment        {"namespace": "search", "deployment": "<my-app>"}
1.6722324337108195e+09  INFO    injecting Jaeger Agent sidecar  {"namespace": "search", "jaeger": "jaeger-search", "jaeger-namespace": "search-tools"}
1.672232433710826e+09   INFO    injecting sidecar       {"instance": "jaeger-search", "namespace": "search-tools", "deployment": "<my-app>"}
```

Here we can see that the operator discovered the new deployment and injected an agent pointing to the Jaeger backend instance `jaeger-search`. We can verify that by inspecting the deployment and noticing that now we have a `jaeger-agent` container inside the pods of our deployment.

## Configuring Kamon to report to the agent
In order to use Kamon to generate spans you need to add the Jaeger reporter dependency: 
`libraryDependencies += "io.kamon" %% "kamon-jaeger" % "2.5.9"`

Furthermore you need to configure the Kamon Jaeger reporter to point to the local Jaeger agent:
```hocon
kamon {

  environment {
    service = "<my-app>"
  }
   
  jaeger {
    host = "localhost" 
    port = 6831
    protocol = udp
  }

  modules {
    jaeger {
      enabled = true
    }
  }
}
```
