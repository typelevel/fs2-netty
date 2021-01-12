/*
 * Copyright 2021 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

name := "fs2-netty"

ThisBuild / baseVersion := "0.1"

ThisBuild / organization := "org.typelevel"
ThisBuild / publishGithubUser := "djspiewak"
ThisBuild / publishFullName := "Daniel Spiewak"

ThisBuild / organization := "org.typelevel"
ThisBuild / organizationName := "Typelevel"

ThisBuild / startYear := Some(2021)

ThisBuild / crossScalaVersions := Seq("2.13.4")

libraryDependencies ++= Seq(
  "io.netty" % "netty-all" % "4.1.56.Final",
  "co.fs2"  %% "fs2-core"  % "3.0-21-1e66f47",

  "org.specs2" %% "specs2-core" % "4.10.5" % Test,
  "com.codecommit" %% "cats-effect-testing-specs2" % "1.0-25-c4685f2" % Test)
