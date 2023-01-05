# Introduction
This document outlines a possible system architecture enabling the collection of tracing information from search services (with the possibility of expanding adoption to other teams).

Goals:
- minimal configuration overhead for application developer
- not too strong ties to particular implementations (i.e. if we decide to change we do not want to change all the code we manually instrumented)
- ease of integration with existing codebases

To achieve these goals the selected tools are:
- OpenTelemetry as a protocol for exposing tracing data from application code
- OpenTelemetry Kubernetes Operator to inject tracing collector into application deployments
- Jaeger as a backend to process and visualize the collected traacing data

## Architecture

```
┌──────────────────────────────────────────────┐ ┌────────────────────────────────┐
│                                  search-tools│ │                         search │
│    ┌───────────┐                             │ │    ┌────────────┐              │
│    │           │                             │ │    │            │              │
│    │  Jaeger   ◄─────────────────────────────┼─┼────┤  OTEL      ├─────────┐    │
│    │  Backend  │                             │ │    │  Collector │         │    │
│    │           │                   ┌─────────┼─┼────►  Resource  │         │    │
│    └─────┬─────┘                   │         │ │    └────────────┘         │    │
│          │                         │         │ │                           │    │
│          │                         │         │ │                           │    │
│          │                         │         │ │    ┌────────────┐         │    │
│          │                         │         │ │    │            │         │    │
│    ┌─────▼──────────┐       ┌──────┴─────┐ ┌─┼─┼────► Application│         │    │
│    │                │       │            │ │ │ │    │ Deployment │         │    │
│    │  Elasticsearch │       │  OTEL      │ │ │ │    │            │         │    │
│    │  Jaeger        │       │  Operator  ├─┘ │ │    └─────┬──────┴────┐    │    │
│    │                │       │            │   │ │          │ OTEL Coll ◄────┘    │
│    └────────────────┘       └────────────┘   │ │          └───────────┘         │
│                                              │ │                                │
└──────────────────────────────────────────────┘ └────────────────────────────────┘
```

The diagram sketches the different components and their interactions.

The architecture is split between the `search-tools` and the `search` namespaces.
In the first we have most of the infrastructure related objects:
- Jaeger Backend
- An Elasticsearch cluster used by Jaeger to store data
- The OpenTelemetry operator

In the latter we have:
- the definition of the tracing collection for the namespace
- the application deployment
- the sidecar collector container injected into the application pod

## Operating Flow
Upon submission of a Kebernetes deployment the following happens:
1. a new application deployment is submitted to Kubernetes in the `search` namespace
2. the OTEL operator checks if the deployment is requesting the injection of a collector container by checking the annotation `sidecar.opentelemetry.io/inject: true`
3. the OTEL operator looks for an OpenTelementry Collector resource in the `search` namespace
4. the OTEL operator creates an OpenTelementry Collector container using the configuration specified in the resource
5. the container is injected into the application deployment
6. the application produces tracing information sending them using the OpenTelemetry protocol to a local endpoint (`localhost:4317`)
7. the OpenTelemetry Collector receives the tracing data from the application and forwards them to the backend configured in the resource (i.e. Jaeger)


## Infrastructure Deployment

### Jaeger backend

The Jaeger backend can be installed using the dedicated Helm [chart](https://github.com/jaegertracing/helm-charts/tree/main/charts/jaeger):

```sh
helm repo add jaegertracing https://jaegertracing.github.io/helm-charts
helm --namespace search-tools install jaeger-search jaegertracing/jaeger --values jaeger-values.yaml
```

As suggested in the Jaeger documentation the storage backend is set to Elasticsearch.
The helm chart takes care of provisioning the Elasticsearch cluster but it is possible to use an existing one.

After the chart is executed we should have 2 new services in the `search-tools` namespace:
- jaeger-collector: the collector endpoint to send trace data to Jaeger (grpc: 14250)
- jaeger-query: webui exposed on port 80 (in place of the usual 16686)

### OpenTelemetry Operator

The operator requires [cert-manager](https://cert-manager.io) which can be installed with Heml:

```sh
helm repo add jetstack https://charts.jetstack.io
helm install \
  cert-manager jetstack/cert-manager \
  --namespace cert-manager \
  --create-namespace \
  --version v1.10.1 \
  --set installCRDs=true
```

The operator can be installed via Helm using the dedicated Helm [chart](https://github.com/open-telemetry/opentelemetry-helm-charts/tree/main/charts/opentelemetry-operator):

```sh
helm repo add open-telemetry https://open-telemetry.github.io/opentelemetry-helm-charts
helm install --namespace search-tools opentelemetry-operator open-telemetry/opentelemetry-operator
```

### OpenTelemetry Collector resource

Once the operator is installed we need to create a `OpenTelemetryCollector` in the namespace we want to target.
The file `opentelemetry-collector.yaml` contains an example configuration for a sidecar collector receiving spans via opentelemetry protocol and exporting them to the jaeger backend via grpc.

Note that in the configuration we directly reference the Jaeger backend using the DNS entry for the `jaeger-collector` service.

The resource can be created in the `search` namespace using the following commands:
```sh
kubectl apply -n search -f opentelemetry-collector.yaml
```

At this point any pod in the `search` namespace with a `sidecar.opentelemetry.io/inject: true` annotation will automatically be injected with an OpenTelemetry sidecar collector forwarding spans to the Jaeger backend.

**NOTE:** as specified [here](https://github.com/open-telemetry/opentelemetry-operator/blob/main/README.md#sidecar-injection) when using deployments, the annotation *MUST* be set inside the spec template:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: my-app
  # ...
  annotations:
    sidecar.opentelemetry.io/inject: "true" # WRONG
spec:
  # ...
  template:
    metadata:
      # ...
      annotations:
        sidecar.opentelemetry.io/inject: "true" # CORRECT
    spec:
      # ...
```

## Adding tracing to applications

To add tracing to an application you can use the OpenTelemetry API (available for many languages including Java, JavaScript, Go, Rust) or other libraries able to send data using the OpenTelemetry protocol (i.e. Kamon).


## Configuring Kamon to report to the agent
In order to use Kamon to generate spans you need to add the OpenTelemetry reporter dependency: 
`libraryDependencies += "io.kamon" %% "kamon-opentracing" % "2.5.9"`

Furthermore you need to configure the Kamon OpeTracing reporter to point to the local collector:
```hocon
kamon {

  environment {
    service = "<my-app>"
  }
   
  otel {
    endpoint = "https://localhost:4317"
    protocol = "grpc"
  }

  modules {
    otel-trace-reporter {
      enabled = true
    }
  }
}
```

## OpenTelemetry collector without Operator

The OpenTelemetry collector can be directly created as a deployment, avoiding the need for the Operator and simplifying the overall system architecture:

```
┌──────────────────────────────────────────────┐ ┌────────────────────────────────┐
│                                  search-tools│ │                         search │
│    ┌───────────┐          ┌────────────┐     │ │      ┌───────────────┐         │
│    │           │          │            │     │ │      │               │         │
│    │  Jaeger   ◄──────────┤  OTEL      │     │ │      │ Application 1 │         │
│    │  Backend  │          │  Collector ◄─────┼─┼──────┤ Deployment    │         │
│    │           │          │            │     │ │      │               │         │
│    └─────┬─────┘          └─────▲──────┘     │ │      └───────────────┘         │
│          │                      │            │ │                                │
│          │                      │            │ │                                │
│          │                      │            │ │                                │
│          │                      │            │ │      ┌───────────────┐         │
│    ┌─────▼──────────┐           │            │ │      │               │         │
│    │                │           │            │ │      │ Application 2 │         │
│    │  Elasticsearch │           └────────────┼─┼──────┤ Deployment    │         │
│    │  Jaeger        │                        │ │      │               │         │
│    │                │                        │ │      └───────────────┘         │
│    └────────────────┘                        │ │                                │
│                                              │ │                                │
└──────────────────────────────────────────────┘ └────────────────────────────────┘
```

OpenTelemetry provides an Helm [chart](https://github.com/open-telemetry/opentelemetry-helm-charts/tree/main/charts/opentelemetry-collector) for installing the collector:

```sh
helm repo add open-telemetry https://open-telemetry.github.io/opentelemetry-helm-charts
helm install --namespace search-tools opentelemetry-collector open-telemetry/opentelemetry-collector --values opentelemetry-collector-deployment-values.yaml
```

The configuration file `opentelemetry-collector-deployment-values.yaml` provides the same configuration options as the one used to create the resource used by the operator.

Application can now send spans to `opentelemetry-collector.search-tools.svc.cluster.local:14250`.

