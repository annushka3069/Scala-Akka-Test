activator-akka-cluster-sharding-scala
=====================================

Activator template for the Akka Cluster Sharding feature. See [tutorial](https://github.com/typesafehub/activator-akka-cluster-sharding-scala/blob/master/tutorial/index.html).



Running
 docker run -i -t --rm --name seed -e CLUSTER_PORT=2552 akka-cluster-sharding-scala:1.0 -d 2552
 docker run -i -t --rm --name c1 --link seed:seed -e CLUSTER_PORT=2552 akka-cluster-sharding-scala:1.0 -d 2552

