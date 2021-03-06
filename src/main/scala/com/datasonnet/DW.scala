package com.datasonnet


import java.net.URL
import java.util.Scanner

import com.datasonnet.spi.UjsonUtil
import com.datasonnet.wrap.Library.library
import fastparse.internal.Logger
import org.slf4j.LoggerFactory
import sjsonnet.Expr.Member.Visibility
import sjsonnet.Expr.Params
import sjsonnet.ReadWriter.StringRead
import sjsonnet.Std._
import sjsonnet.{Applyer, Materializer, Val}

import scala.util.Random
import scala.util.matching.Regex

object DW {
  val libraries = Map(
    "Core" -> library(

      builtin("abs", "num"){
        (_,_, num: Double) =>
          Math.abs(num);
      },

      // See: https://damieng.com/blog/2014/12/11/sequence-averages-in-scala
      // See: https://gist.github.com/gclaramunt/5710280
      builtin("avg", "array") {
        (_, _, array: Val.Arr) =>
          val (sum, length) = array.value.foldLeft((0.0, 0)) (
            {
              case ((sum, length), num) => (sum + num.force.asInstanceOf[Val.Num].value, 1 + length)
            }
          )
          sum / length
      },

      builtin("ceil", "num"){
        (_,_, num: Double) =>
          Math.ceil(num);
      },

      builtin("contains", "container", "value"){
        (ev,_, container: Val, value: Val) =>
        container match{
          // See: scala.collection.IterableOnceOps.exists
          case Val.Arr(array) =>
              val evald = Materializer(value)(ev)
              var res = false
              val it = array.iterator
              while (!res && it.hasNext) {
                res = Materializer(it.next().force)(ev) == evald
              }
              res

            case Val.Str(s) =>
              val regex = value.cast[Val.Str].value.r
              regex.findAllMatchIn(s).toSeq.nonEmpty;

            case _ => throw new IllegalArgumentException(
              "Expected Array or String, got: " + container.prettyName);
          }
      },

      builtin("distinctBy", "container", "funct"){
        (ev,fs, container: Val, funct: Applyer) =>
          container match {
            case Val.Arr(s) =>
              val out = collection.mutable.Buffer.empty[Val.Lazy]
              for((item,index) <- s.zipWithIndex){
                var contains = false
                for((outItem,outIndex) <- out.zipWithIndex){
                  if(funct.apply(outItem,Val.Lazy(Val.Num(outIndex))) == funct.apply(item,Val.Lazy(Val.Num(index)))){
                    contains = true
                  }
                }
                if(!contains)
                  out.append(item)
              }
              Val.Arr(out.toSeq)
            case s: Val.Obj =>
              val out =scala.collection.mutable.Map[String, Val.Obj.Member]()
              // new java.util.HashMap[String, Object]()//
              for((key,hidden) <- s.getVisibleKeys()){

                var contains = false
                val outObj =  new Val.Obj(out, _ => (), None)
                for((outKey,outHidden) <- outObj.getVisibleKeys()){
                  if(funct.apply(Val.Lazy(outObj.value(outKey, -1)(fs ,ev)), Val.Lazy(Val.Str(outKey))) == funct.apply(Val.Lazy(s.value(key, -1)(fs ,ev)), Val.Lazy(Val.Str(outKey)))){
                    contains = true
                  }
                }
                if(!contains){
                  out. += (key -> Val.Obj.Member(add = false, Visibility.Normal, (_, _, _, _) => s.value(key, -1)(fs,ev)))
                }
              }
              new Val.Obj(out, _ => (), None)
            case _ =>
              throw new IllegalArgumentException(
                "Expected Array or Object, got: " + container.prettyName);
          }
      },

      builtin("endsWith", "main", "sub"){
        (_,_, main: String, sub: String) =>
          main.toUpperCase.endsWith(sub.toUpperCase);
      },

      builtin("entriesOf", "obj"){
        (ev,fs, obj: Val.Obj) =>

          val out = collection.mutable.Buffer.empty[Val.Lazy]
          for((key,hidden) <- obj.getVisibleKeys()){
            val currentObj = scala.collection.mutable.Map[String, Val.Obj.Member]()
            currentObj += ("key" -> Val.Obj.Member(add =false, Visibility.Normal, (_, _, _, _) => Val.Lazy(Val.Str(key)).force))
            currentObj += ("value" -> Val.Obj.Member(add =false, Visibility.Normal, (_, _, _, _) => obj.value(key, -1)(fs, ev)))
            //add key to currentObj
            out.append(Val.Lazy(new Val.Obj(currentObj, _ => (), None)))
          }
          Val.Arr(out.toSeq)
      },

      builtin("filter", "array", "funct"){
        (_, _, array: Val.Arr, funct: Applyer) =>
          Val.Arr(array.value
            .zipWithIndex
            .filter({
              case (lazyItem, index) => funct.apply(lazyItem, Val.Lazy(Val.Num(index))) == Val.True
            })
            .map(_._1))
      },

      builtin("filterObject", "obj", "func"){
        (ev,fs, obj: Val.Obj, func: Applyer) =>
          val out = scala.collection.mutable.Map[String, Val.Obj.Member]()

          for(((key,hidden),index) <- obj.getVisibleKeys().zipWithIndex){

            val functBool = func.apply(Val.Lazy(obj.value(key,-1)(fs, ev)), Val.Lazy(Val.Str(key)), Val.Lazy(Val.Num(index)))
            if(functBool == Val.True){
              out. += (key -> Val.Obj.Member(add = false, Visibility.Normal, (_, _, _, _) => obj.value(key, -1)(fs,ev)))
            }
          }
          new Val.Obj(out, _ => (), None)
      },

      builtin("find", "container", "value"){
        (ev,_, container: Val, value: Val) =>
          container match {
            case Val.Str(str) =>
              val out = collection.mutable.Buffer.empty[Val.Lazy]
              val sub = value.cast[Val.Str].value
              if(sub.startsWith("/") && sub.endsWith("/")){
                //REGEX
                val pattern=sub.substring(1,sub.length-1).r
                for(loc <- pattern.findAllMatchIn(str).map(_.start)){
                  out+=(Val.Lazy(Val.Num(loc)))
                }
              }
              else{
                //Normal String
                var totalLength = 0;

                for(loc <- sub.r.findAllMatchIn(str).map(_.start)){
                  //totalLength+=newStr.length;
                  out.append(Val.Lazy(Val.Num(loc)))
                }
              }
              Val.Arr(out.toSeq)
            case Val.Arr(s) =>
              Val.Arr(
                for(
                  (v,i) <- s.zipWithIndex
                  if Materializer(v.force)(ev) == Materializer(value)(ev)
                ) yield Val.Lazy(Val.Num(i))
              )

            case _ => throw new IllegalArgumentException(
              "Expected Array or String, got: " + container.prettyName);
          }
      },

      builtin("flatMap", "array", "funct"){
        (_,_, array: Val, funct: Applyer) =>
          array match {
            case Val.Arr(s) =>
              val out = collection.mutable.Buffer.empty[Val.Lazy]
              for((v,i) <- s.zipWithIndex){
                v.force match {
                  case Val.Arr(inner) =>
                    for((inV,inI) <- inner.zipWithIndex){
                      out+=Val.Lazy(funct.apply(inV,Val.Lazy(Val.Num(inI))))
                    }
                  case _ =>  throw new IllegalArgumentException(
                    "Expected Array of Arrays, got: Array of " + v.force.prettyName);
                }
              }
              Val.Arr(out.toSeq);
            case Val.Null => Materializer.reverse(UjsonUtil.jsonObjectValueOf("null"));
            case _ =>  throw new IllegalArgumentException(
              "Expected Array, got: " + array.prettyName);
          }
      },

      builtin("flatten", "array"){
        (_,_, array: Val.Arr) =>
          val out = collection.mutable.Buffer.empty[Val.Lazy]
          for(x <- array.value){
            x.force match{
              case Val.Null =>
              case Val.Arr(v) => out.appendAll(v)
              case _ => throw new IllegalArgumentException(
                "Expected Array, got: " + array.prettyName);
            }
          }
          Val.Arr(out.toSeq)
      },

      builtin("floor", "num"){
        (_,_, num: Double) =>
          Math.floor(num);
      },

      builtin("groupBy", "container", "funct"){
        (ev,fs, container: Val, funct: Applyer) =>
          container match{
            case Val.Arr(s) =>
              val out = scala.collection.mutable.Map[String, Val.Obj.Member]()
              for((item,index) <- s.zipWithIndex){

                val key = funct.apply(item, Val.Lazy(Val.Num(index)))
                if(!new Val.Obj(out, _ => (), None)
                      .getVisibleKeys()
                      .contains(key.cast[Val.Str].value)){

                  val array = collection.mutable.Buffer.empty[Val.Lazy]
                  for((item2,index2) <- s.zipWithIndex){
                    val compare = funct.apply(item2, Val.Lazy(Val.Num(index2)))
                    if(key == compare){
                      array.append(item2)
                    }
                  }
                  out += (key.cast[Val.Str].value -> Val.Obj.Member(add = false, Visibility.Normal, (_, _, _, _) => Val.Arr(array.toSeq)))
                }
              }
              new Val.Obj(out, _ => (), None)
            case s: Val.Obj =>
              val out = scala.collection.mutable.Map[String, Val.Obj.Member]()
              for((key,hidden) <- s.getVisibleKeys()){
                val functKey = funct.apply(Val.Lazy(s.value(key,-1)(fs, ev)), Val.Lazy(Val.Str(key)))

                if(!new Val.Obj(out, _ => (), None)
                  .getVisibleKeys()
                  .contains(functKey.cast[Val.Str].value)){

                  val currentObj = scala.collection.mutable.Map[String, Val.Obj.Member]()
                  for((key2,hidden2) <- s.getVisibleKeys()) {
                    val compare = funct.apply(Val.Lazy(s.value(key2,-1)(fs, ev)), Val.Lazy(Val.Str(key2)))
                    if (functKey == compare){
                      currentObj += (key2 -> Val.Obj.Member(add = false, Visibility.Normal, (_, _, _, _) => s.value(key2,-1)(fs,ev)))
                    } // do nothing
                  }
                  out += (functKey.cast[Val.Str].value -> Val.Obj.Member(add = false, Visibility.Normal, (_, _, _, _) => new Val.Obj(currentObj, _ => (), None)))
                }
              }
              new Val.Obj(out, _ => (), None)
            case Val.Null => Materializer.reverse(UjsonUtil.jsonObjectValueOf("null"));
            case _ => throw new IllegalArgumentException(
              "Expected Array or Object, got: " + container.prettyName);
          }
      },

      builtin("isBlank", "value"){
        (_,_, value: Val) =>
          value match {
            case Val.Str(s) => s.trim().isEmpty
            case Val.Null => true
            case _ => throw new IllegalArgumentException(
              "Expected String, got: " + value.prettyName);
          }
      },

      builtin("isDecimal", "value"){
        (_,_, value: Double) =>
          (Math.ceil(value) != Math.floor(value)).booleanValue()
      },

      builtin("isEmpty", "container"){
        (_,_, container: Val) =>
          container match{
            case Val.Null => true
            case Val.Str(s) => s.isEmpty.booleanValue()
            case Val.Arr(s) => s.isEmpty.booleanValue()
            case s: Val.Obj => s.getVisibleKeys().isEmpty.booleanValue()
            case _ => throw new IllegalArgumentException(
              "Expected String, Array, or Object, got: " + container.prettyName);
          }
      },

      builtin("isEven", "num"){
        (_, _, num: Double) =>
          (num % 2) == 0
      },

      builtin("isInteger", "value") {
        (_,_, value: Double) =>
          (Math.ceil(value) == Math.floor(value)).booleanValue()
      },

      //TODO
      builtin("isLeapYear", "value"){
        (_,_, _: Val) =>
          Materializer.reverse(UjsonUtil.jsonObjectValueOf("null"));
      },

      builtin("isOdd", "num"){
        (_, _, num: Double) =>
          (num % 2) != 0
      },

      builtin("joinBy", "array", "sep"){
        (_,_, array: Val.Arr, sep: String) =>
          var acc = ""
          for(x <- array.value){
            val value = x.force match{
              case Val.Str(x) => x
              case Val.True => "true"
              case Val.False => "false"
              case Val.Num(x) => if(Math.ceil(x) != Math.floor(x)) x.toString else x.intValue().toString
              case _ => throw new IllegalArgumentException(
                "Expected String, Number, Boolean, got: " + x.force.prettyName);
            }
            if(acc.isEmpty) acc += value else acc += (sep + value)
          }
          acc
      },

      builtin("distinctByT", "container", "funct") {
        (ev, fs, container: Val, funct: Applyer) =>
          container match {
            case Val.Arr(s) =>
              val out = collection.mutable.Buffer.empty[Val.Lazy]
              for ((item, index) <- s.zipWithIndex) {
                  out.append(item)
              }
              Val.Arr(out.toSeq)
          }
      },

      builtin("keysOf", "obj"){
        (_,_, obj: Val.Obj) =>
          val out = collection.mutable.Buffer.empty[Val.Lazy]
          for((key,hidden) <- obj.getVisibleKeys()){
            out.append(Val.Lazy(Val.Str(key)))
          }
          Val.Arr(out.toSeq)
      },

      builtin("lower", "str"){
        (_,_, str: String) =>
          str.toLowerCase();
      },

      builtin("map", "array", "funct"){
        (_, _, array: Val, funct: Applyer) =>
          array match {
            case Val.Null => Materializer.reverse(UjsonUtil.jsonObjectValueOf("null"));
            case Val.Arr(seq) =>
              Val.Arr(
                seq.zipWithIndex.map{
                  case(item, index) => Val.Lazy(funct.apply(item, Val.Lazy(Val.Num(index))))
                }
              )
            case _ => throw new IllegalArgumentException(
              "Expected Array, got: " + array.prettyName);
          }
      },

      builtin("mapObject", "value", "funct"){
        (ev,fs, value: Val, funct: Applyer) =>
          value match{
            case obj: Val.Obj =>
              val out = scala.collection.mutable.Map[String, Val.Obj.Member]()
              for(((key,hidden), index) <- obj.getVisibleKeys().zipWithIndex){

                val funcReturn = funct.apply(Val.Lazy(obj.value(key,-1)(fs, ev)), Val.Lazy(Val.Str(key)), Val.Lazy(Val.Num(index)))
                funcReturn match{
                  case s: Val.Obj =>
                    for((sKey,sHidden) <- s.getVisibleKeys()) {
                      out += (sKey -> Val.Obj.Member(add =false, Visibility.Normal, (_, _, _, _) => s.value(sKey, -1)(fs, ev)))
                    }
                  case _ =>  throw new IllegalArgumentException(
                    "Function must return an object, got: " + funcReturn.prettyName);
                }

              }
              new Val.Obj(out, _ => (), None)
            case Val.Null => Materializer.reverse(UjsonUtil.jsonObjectValueOf("null"));
          }
      },

      builtin("match", "string", "reg") {
        (_, _, string: String, reg: String) =>
            val regex = reg.r
            val out=collection.mutable.Buffer.empty[Val.Lazy]
            for( word <- regex.findAllMatchIn(string)){
              for(index <- 0 to word.groupCount){
                out+=Val.Lazy(Val.Str(word.group(index)))
              }
            }
          Val.Arr(out.toSeq)
      },

      builtin("matches", "string", "reg") {
        (_,_, string: String, reg: String) =>
            val regex = reg.r;
            regex.matches(string);
      },

      builtin("max", "array"){
        (ev,fs, array: Val.Arr) =>
          var value = array.value.head
          for (x <- array.value) {
            value.force.prettyName match {
              case "string" =>
                if (value.force.cast[Val.Str].value < x.force.cast[Val.Str].value) {
                  value = x
                }
              case "boolean" =>
                if (x.force == Val.Lazy(Val.True).force) {
                  value = x
                }
              case "number" =>
                if (value.force.cast[Val.Num].value < x.force.cast[Val.Num].value) {
                  value = x
                }
              case i => throw new IllegalArgumentException(
                "Array must be of type string,boolean, or number; got: " + i);
            }
          }
          value.force
      },

      builtin("maxBy", "array", "funct"){
        (_,_, array: Val.Arr, funct: Applyer) =>
          var value = array.value.head
          val compareType=funct.apply(value).prettyName
          for (x <- array.value) {
            compareType match {
              case "string" =>
                if (funct.apply(value).toString < funct.apply(x).toString) {
                  value = x
                }
              case "boolean" =>
                if (funct.apply(x) == Val.Lazy(Val.True).force) {
                  value = x
                }
              case "number" =>
                if (funct.apply(value).cast[Val.Num].value < funct.apply(x).cast[Val.Num].value) {
                  value = x
                }
              case i => throw new IllegalArgumentException(
                "Array must be of type string,boolean, or number; got: " + i);
            }
          }
          value.force
      },

      builtin("min", "array"){
        (_,_, array: Val.Arr) =>
          var value = array.value.head
          for (x <- array.value) {
            value.force.prettyName match {
              case "string" =>
                if (value.force.cast[Val.Str].value > x.force.cast[Val.Str].value) {
                  value = x
                }
              case "boolean" =>
                if (x.force == Val.Lazy(Val.False).force) {
                  value = x
                }
              case "number" =>
                if (value.force.cast[Val.Num].value > x.force.cast[Val.Num].value) {
                  value = x
                }
              case i => throw new IllegalArgumentException(
                "Array must be of type string,boolean, or number; got: " + i);
            }
          }
          value.force
      },

      builtin("minBy", "array", "funct"){
        (_,_, array: Val.Arr, funct: Applyer) =>
          var value = array.value.head
          val compareType=funct.apply(value).prettyName
          for (x <- array.value) {
            compareType match {
              case "string" =>
                if (funct.apply(value).cast[Val.Str].value > funct.apply(x).cast[Val.Str].value) {
                  value = x
                }
              case "boolean" =>
                if (funct.apply(x) == Val.Lazy(Val.False).force) {
                  value = x
                }
              case "number" =>
                if (funct.apply(value).cast[Val.Num].value > funct.apply(x).cast[Val.Num].value) {
                  value = x
                }
              case i => throw new IllegalArgumentException(
                "Array must be of type string,boolean, or number; got: " + i);
            }
          }
          value.force
      },

      builtin("mod", "num1", "num2"){
        (_,_, num1: Double, num2: Double) =>
          num1 % num2;
      },

      builtin("namesOf", "obj"){
        (_,_, obj: Val.Obj) =>
          val out = collection.mutable.Buffer.empty[Val.Lazy]
          for((key,hidden) <- obj.getVisibleKeys()){
            out.append(Val.Lazy(Val.Str(key)))
          }
          Val.Arr(out.toSeq)
      },

      builtin("orderBy", "value", "funct"){
        (_,_, value: Val, funct: Applyer) =>
          value match{
            case Val.Arr(array) =>

              val out = collection.mutable.Buffer.empty[Val.Lazy]
              out.appendAll(array)
              for((item,i) <- (out.zipWithIndex)){
                for(j <- i until(0,-1)){
                  if(
                      funct.apply(out(j)).toString < funct.apply(out(j-1)).toString
                  ){
                    val temp = out(j)
                    out(j) = out(j-1)
                    out(j-1) = temp
                  }
                }
              }
              Val.Arr(out.toSeq)

            case _: Val.Obj => Materializer.reverse(UjsonUtil.jsonObjectValueOf("null"));
            case Val.Null => Materializer.reverse(UjsonUtil.jsonObjectValueOf("null"));
            case _ => throw new IllegalArgumentException(
              "Expected Array or Object got: " + value.prettyName);
          }
      },

      builtin("pluck", "value", "funct"){
        (ev,fs, value: Val, funct: Applyer) =>
          value match{
            case obj: Val.Obj =>
              val out = collection.mutable.Buffer.empty[Val.Lazy]
              for(((key,hidden), index) <- obj.getVisibleKeys().zipWithIndex){
                out.append(Val.Lazy(funct.apply(Val.Lazy(obj.value(key,-1)(fs, ev)), Val.Lazy(Val.Str(key)), Val.Lazy(Val.Num(index)))))
              }
              Val.Arr(out.toSeq)
            case Val.Null => Materializer.reverse(UjsonUtil.jsonObjectValueOf("null"));
          }
      },

      builtin("pow", "num1", "num2"){
        (_,_, num1: Double, num2: Double) =>
          Math.pow(num1,num2)
      },

      builtin0("random") {
        (_, _,_) =>
          (0.0 + (1.0 - 0.0) * Random.nextDouble()).doubleValue()
      },

      builtin("randomint", "num") {
        (_,_, num: Int) =>
          (Random.nextInt((num - 0) + 1) + 0).intValue()
      },

      //TODO
      builtin("read", "value"){
        (_,_, _: Val) =>
          Materializer.reverse(UjsonUtil.jsonObjectValueOf("null"));
      },

      builtin("readUrl", "url"){
        (_,_, url: String) =>
          val out = new Scanner(new URL(url).openStream(), "UTF-8").useDelimiter("\\A").next()
          Materializer.reverse(UjsonUtil.jsonObjectValueOf(out));
      },

      //TODO needs work
      builtin("reduce", "array", "funct", "init"){
        (_,_, array: Val.Arr, funct: Applyer, init: Val) =>
          var acc: Val = init;
          for(item <- array.value){
            acc=funct.apply(item,Val.Lazy(acc));
          }
          acc
      },


      builtin("replace", "string", "regex", "replacement") {
        (_,_, str: String, reg: String, replacement: String) =>
            val regex = reg.r
            regex.replaceAllIn(str,replacement)
          //Materializer.reverse(UjsonUtil.jsonObjectValueOf(com.datasonnet.DWCore.replace(string, regex, replacement)));
      },

      builtin("round", "num") {
        (_,_, num: Double) =>
          Math.round(num).intValue()
      },

      builtin("scan", "str", "sub"){
        (_,_, str: String, sub: String) =>
          val regex = sub.r;
          val out = collection.mutable.Buffer.empty[Val.Lazy]

          for(item <- regex.findAllMatchIn(str)){
            val subOut = collection.mutable.Buffer.empty[Val.Lazy]
            for(index <- 0 to item.groupCount){
              subOut+=Val.Lazy(Val.Str(item.group(index)))
            }
            out.append(Val.Lazy(Val.Arr(subOut.toSeq)))
          }
          Val.Arr(out.toSeq);
      },

      builtin("sizeOf", "value"){
        (_,_, value: Val) =>
          value match {
            case Val.Str(s) => s.length()
            case s: Val.Obj => s.getVisibleKeys().size
            case Val.Arr(s) => s.size
            case Val.Null => 0
            case _ => throw new IllegalArgumentException(
              "Expected Array, String, Object got: " + value.prettyName);
          }
      },

      //TODO String,String String,Regex
      builtin("splitBy", "str", "value"){
        (_,_, str: String, value: String) =>
          val out = collection.mutable.Buffer.empty[Val.Lazy];
          val regex=value.r;
          for(words <- regex.split(str)){
            out+=Val.Lazy(Val.Str(words))
          }
          //Materializer.reverse(UjsonUtil.jsonObjectValueOf("null"));
          Val.Arr(out.toSeq)
      },

      builtin("sqrt", "num"){
        (_,_, num: Double) =>
          Math.sqrt(num)
      },

      builtin("startsWith", "str1", "str2"){
        (_,_, str1: String, str2: String) =>
          str1.toUpperCase().startsWith(str2.toUpperCase());
      },

      builtin("sum", "array"){
        (_,_, array: Val.Arr) =>
          var total = 0.0
          for(x <- array.value){
            x.force match {
              case Val.Num(s) => total += s
              case _ => throw new IllegalArgumentException(
                "Expected Array of numbers got: " + x.force.prettyName);
            }
          }
          total
      },

      builtin("to", "begin", "end"){
        (_,_, begin: Int, end: Int) =>
          Val.Arr(
            (begin until (end+1)).map( i =>
              Val.Lazy(Val.Num(i))
            )
          )
      },

      builtin("trim", "str"){
        (_,_, str: String) =>
          str.trim()
      },

      builtin("typeOf", "value"){
        (_,_, value: Val) =>
          value match{
            case Val.True | Val.False => "boolean"
            case Val.Null => "null"
            case _: Val.Obj => "object"
            case _: Val.Arr => "array"
            case _: Val.Func => "function"
            case _: Val.Num => "number"
            case _: Val.Str => "string"
          }
      },

      builtin("unzip", "array"){
        (_, _, array: Val.Arr) =>
          var size = Int.MaxValue
          for(v <- array.value){
            v.force match{
              case Val.Arr(s) => if(s.size < size) size = s.size
              case _ =>   throw new IllegalArgumentException(
                "Expected Array, got: " + v.force.prettyName);
            }
          }
          val out = collection.mutable.Buffer.empty[Val.Lazy]
          for(i <-0 until size) {
            val current = collection.mutable.Buffer.empty[Val.Lazy]
            for (x <- array.value) {
              x.force match{
                case Val.Arr(s) => current.append(s(i))
              }
            }
            out.append(Val.Lazy(Val.Arr(current.toSeq)))
          }
          Val.Arr(out.toSeq)
      },

      builtin("upper", "str"){
        (_,_, str: String) =>
          str.toUpperCase()
      },

      builtin0("uuid") {
        (_, _, _) =>
          Materializer.reverse(UjsonUtil.jsonObjectValueOf(com.datasonnet.DWCore.uuid()));
      },

      builtin("valuesOf", "obj"){
        (ev,fs, obj: Val.Obj) =>
          val out = collection.mutable.Buffer.empty[Val.Lazy]
          for((key,hidden) <- obj.getVisibleKeys()){
            out.append(Val.Lazy(obj.value(key, -1)(fs, ev)))
          }
          Val.Arr(out.toSeq)
      },

      builtin("zip", "array1", "array2") {
        (_, _, array1: Val.Arr, array2: Val.Arr) =>

          val smallArray = if(array1.value.size <= array2.value.size) array1 else array2
          val bigArray = (if(smallArray == array1) array2 else array1).value
          val out = collection.mutable.Buffer.empty[Val.Lazy]
          for((v,i) <- smallArray.value.zipWithIndex){
            val current = collection.mutable.Buffer.empty[Val.Lazy]
            if(smallArray == array1) {
              current.append(v)
              current.append(bigArray(i))
            }
            else{
              current.append(bigArray(i))
              current.append(v)
            }
            out.append(Val.Lazy(Val.Arr(current.toSeq)))
          }
          Val.Arr(out.toSeq)
      }
    )
  )
}
