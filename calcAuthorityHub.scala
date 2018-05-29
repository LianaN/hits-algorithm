/**
    * Calculates authorities and hubs using the HITS algorithm
    * IMPORTANT: graphDF is a DataFrame that contains two columns "srcId" and "dstId" 
    * that correspond to source and target nodes of arcs of a graph.
    * @param spark
    * @param graphDF
    * @return
    */
  def calcAuthorityHub(spark: SparkSession,
                       graphDF: DataFrame): (DataFrame, DataFrame) = {

    println("Calculate authorities and hubs using the HITS algorithm")

    import spark.sqlContext.implicits._

    // Convergence criterion
    val epsilon: Double = 0.0001
    var continue: Boolean = true
    var k: Int = 0

    // Initialize hub and authority values
    var authhub = graphDF
      .withColumn("hub", lit(1))
      .withColumn("auth", lit(1))

    var auth: DataFrame = null
    var hub: DataFrame = null
    var norm_i, norm_j, hub_diff, auth_diff: Double = 0

    while (continue) {

      // Update the authority value of each node to be the sum of the hub values for every node it has a link into
      auth = authhub
        // Counts the number of incoming nodes of each node
        .groupBy($"dstId")
        .agg(sum("hub") as "auth_temp")
        .na.fill(0)
        .withColumn("norm_i", round(pow("auth_temp", 2), 4))

      // Normalize the authority scores for all nodes by normalizing each value by the sum for each value
      norm_i = math.sqrt(auth.select($"norm_i").rdd.map(_ (0).asInstanceOf[Double]).reduce(_ + _))
      auth = auth.withColumn("auth", $"auth_temp" / norm_i)

      // Update the hub values for each node to be the sum of the authority values that it has a link into
      hub = authhub
        // Counts the number of outgoing nodes of each node
        .groupBy($"srcId")
        .agg(sum("auth") as "hub_temp")
        .na.fill(0)
        .withColumn("norm_j", round(pow("hub_temp", 2), 4))

      // Normalize the hub scores for all nodes by normalizing each value by the system sum for each value
      norm_j = math.sqrt(hub.select($"norm_j").rdd.map(_ (0).asInstanceOf[Double]).reduce(_ + _))
      hub = hub.withColumn("hub", round($"hub_temp" / norm_j, 4))

      hub_diff = authhub
        .withColumnRenamed("hub", "hub_old")
        .join(hub.select("srcId", "hub"), Seq("srcId"), "left")
        .withColumn("hub_diff", abs($"hub" - $"hub_old") / $"hub_old")
        .select("hub_diff").rdd.map(_ (0).asInstanceOf[Double]).reduce(_ + _)

      auth_diff = authhub
        .withColumnRenamed("auth", "auth_old")
        .join(auth.select("dstId", "auth"), Seq("dstId"), "left")
        .withColumn("auth_diff", abs($"auth" - $"auth_old") / $"auth_old")
        .select("auth_diff").rdd.map(_ (0).asInstanceOf[Double]).reduce(_ + _)

      // Update old hub and authority values by the new ones
      authhub = authhub.drop("hub").join(hub.select("srcId", "hub"), Seq("srcId"), "left")
      authhub = authhub.drop("auth").join(auth.select("dstId", "auth"), Seq("dstId"), "left")

      k = k + 1

      // Check the convergence criterion
      continue = (k < 1) && (hub_diff > epsilon) && (auth_diff > epsilon) && (authhub.select("hub").filter($"hub" < 0).count() >= 0) && (authhub.select("auth").filter($"auth" < 0).count() >= 0)

    }
    (auth, hub)
  }
