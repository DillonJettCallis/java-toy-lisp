package com.redgear.javalisp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Created by DillonJettCallis on 8/13/2016.
 */
public class Lisp {

	private static final Logger log = LoggerFactory.getLogger(Lisp.class);
	private static Lisp mainLisp;

	private final Context lib = Context.createContext();
	private final Map<String, Context> packages = new HashMap<>();
	private final Path mainPack;

	public Lisp(String mainPack) {
		this.mainPack = Paths.get(mainPack).normalize();
	}

	public static void main(String[] args) {

		try {

			mainLisp = new Lisp("./src/main/resources/example.lisp");
			mainLisp.importPackage(".");

		} catch (Exception e) {
			log.error("Fatal exception: ", e);
		}
	}

	private Context importPackage(String fileName) {
		String file = mainPack.resolve(fileName).toAbsolutePath().toString();

		if(packages.containsKey(file)) {
			return packages.get(file);
		} else {
			Context pack = null;
			try {
				pack = loadPackage(file, lib);
			} catch (IOException e) {
				throw new RuntimeException("Failed to import file: " + file, e);
			}

			packages.put(file, pack);

			return pack;
		}
	}

	private Context loadPackage(String fileName, Context lib) throws IOException {
		Deque<String> tokens = tokenize(fileName);

//			tokens.stream().filter(s -> s.contains("(") || s.contains(")") && s.length() > 1).forEach(log::info);

		Expression exprs = parens(tokens, new ArrayList<>());

//			log.info("Expression: {}", exprs.toString());

		Context packageContext = Context.createContext(lib, true);

		exprs.eval(packageContext);

		return packageContext;
	}

	private interface LispFunction extends BiFunction<List<Object>, Context, Object> {

	}

	private interface LispMacroFunction extends BiFunction<List<Expression>, Context, Object> {

	}

	private interface Context {

		boolean containsRef(String key);

		Object getRef(String key);

		void putRef(String key, Object value);

		Context getParent();

		default void addFunction(String name, LispFunction func) {
			putRef(name, func);
		}

		default void addMacroFunction(String name, LispMacroFunction func) {
			putRef(name, func);
		}

		static Context createContext(Context parent, boolean packageScope) {
			return new ContextImpl(parent, packageScope);
		}

		static Context createContext(Context parent, List<Context> imports) {
			return new ImportContext(parent, imports);
		}

		static Context createContext() {
			return createContext(new LibraryContext(), false);
		}

	}

	private static class ContextImpl implements Context {

		private final Context parent;
		private final boolean packageScope;
		private final Map<String, Object> map = new HashMap<>();

		ContextImpl(Context parent, boolean packageScope) {
			this.parent = parent;
			this.packageScope = packageScope;
		}

		public boolean containsRef(String key) {
			return map.containsKey(key) || parent.containsRef(key);
		}

		public Object getRef(String key) {
			if(map.containsKey(key))
				return map.get(key);
			else if(parent.containsRef(key))
				return parent.getRef(key);
			else
				throw new RuntimeException("Undefined ref: " + key);
		}

		public void putRef(String key, Object value) {
			map.put(key, value);
		}

		@Override
		public Context getParent() {
			return packageScope ? this : parent.getParent();
		}

		public String toString() {
			return map.toString();
		}
	}

	private static class ImportContext implements Context {

		private final Context parent;
		private final List<Context> imports;
		private final Map<String, Object> map = new HashMap<>();

		ImportContext(Context parent, List<Context> imports) {
			this.parent = parent;
			this.imports = imports;
		}

		public boolean containsRef(String key) {
			return map.containsKey(key) || parent.containsRef(key) || imports.stream().anyMatch(c -> c.containsRef(key));
		}

		public Object getRef(String key) {
			if(map.containsKey(key))
				return map.get(key);
			else if(parent.containsRef(key))
				return parent.getRef(key);
			else
				return imports.stream().filter(c -> c.containsRef(key)).map(c -> c.getRef(key)).findFirst().orElseThrow(() -> new RuntimeException("Undefined ref: " + key));
		}

		public void putRef(String key, Object value) {
			map.put(key, value);
		}

		@Override
		public Context getParent() {
			return parent.getParent();
		}

		public String toString() {
			return map.toString();
		}
	}

	private static class LibraryContext implements Context {

		private Map<String, Object> map = new HashMap<>();

		private Object prepareForEquals(Object obj) {
			if(obj instanceof BigDecimal) {
				return ((BigDecimal) obj).stripTrailingZeros();
			} else
				return obj;
		}

		LibraryContext() {
			map.put("true",  true);
			map.put("false", false);

			addFunction("eval", (args, context) -> {

				if(args.isEmpty()) {
					throw new RuntimeException("function eval requires at least one argument");
				}

				List<Object> result = args.stream().map(Expression.class::cast).map(ex -> ex.eval(context)).collect(Collectors.toList());

				if(result.size() == 1)
					return result.get(0);
				else
					return result;
			});

			addFunction("apply", (args, context) -> {
				if(args.isEmpty()) {
					throw new RuntimeException("function apply requires at least one argument");
				}

				Object head = args.remove(0);

				if(!(head instanceof LispFunction)) {
					throw new RuntimeException("function apply requires first argument to be a LispFunction, found: " + head.getClass());
				}

				LispFunction func = (LispFunction) head;

				return func.apply(args, context);
			});

			addMacroFunction("if", (expressions, context) -> {
				if(expressions.size() < 2 || expressions.size() > 3) {
					throw new RuntimeException("function if requires exactly two or three arguments, found: " + expressions.size());
				}

				Boolean bool = (Boolean) expressions.get(0).eval(context);

				if(bool) {
					return expressions.get(1).eval(context);
				} else if (expressions.size() > 2) {
					return expressions.get(2).eval(context);
				} else {
					return null;
				}
			});

			addMacroFunction("fn", ((expressions, context) -> {

				if(expressions.size() == 0) {
					throw new RuntimeException("function fn requires at least one argument.");
				}

				Expression body = expressions.remove(expressions.size() - 1);

				List<String> ids = expressions.stream().map(ex -> {
					if(ex instanceof Identifier) {
						return ((Identifier) ex).name;
					} else{
						throw new RuntimeException("function fn expects all arguments except the last one to be Identifiers, found: " + ex.getClass());
					}
				}).collect(Collectors.toList());

				return (LispFunction) (args, fnContext) -> {
					Context inner = Context.createContext(context, false);

					for(int i = 0; i < args.size() && i < ids.size(); i++) {
						inner.putRef(ids.get(i), args.get(i));
					}

					return body.eval(inner);
				};
			}));

			addMacroFunction("macro", ((expressions, context) -> {

				if(expressions.size() == 0) {
					throw new RuntimeException("function macro requires at least one argument.");
				}

				Object uncheckedBody = expressions.remove(expressions.size() - 1);

				if(!(uncheckedBody instanceof SExpression)) {
					throw new RuntimeException("Last argument to function macro must be as SExpression, found: " + uncheckedBody.getClass());
				}

				@SuppressWarnings("unchecked")
				SExpression body = (SExpression) uncheckedBody;

				List<String> ids = expressions.stream().map(ex -> {
					if(ex instanceof Identifier) {
						return ((Identifier) ex).name;
					} else{
						throw new RuntimeException("function macro expects all arguments except the last one to be Identifiers, found: " + ex.getClass());
					}
				}).collect(Collectors.toList());

				return (LispMacroFunction) (args, fnContext) -> {
					Context inner = Context.createContext(context, false);

					for(int i = 0; i < args.size() && i < ids.size(); i++) {
						inner.putRef(ids.get(i), args.get(i));
					}

					return body.eval(inner);
				};
			}));

			addMacroFunction("let", ((expressions, context) -> {
				if(expressions.size() < 2) {
					throw new RuntimeException("function let requires at least three arguments, found: " + expressions.size());
				}

				if(expressions.size() % 2 == 0) {
					throw new RuntimeException("function let requires an odd number of arguments, pairs of key values followed by a final expression. Found: " + expressions.size());
				}

				Expression body = expressions.remove(expressions.size() - 1);

				Context inner = Context.createContext(context, false);
				int arity = 1;

				while(!expressions.isEmpty()) {

					Object uncheckedId = expressions.remove(0);


					if(!(uncheckedId instanceof Identifier)) {
						throw new RuntimeException("function let expected to find Identifier at arity " + arity + " but found: " + uncheckedId.getClass());
					}

					arity++;

					String id = ((Identifier) uncheckedId).name;

					Object value = expressions.remove(0).eval(context);
					arity++;

					inner.putRef(id, value);
				}

				return body.eval(inner);
			}));

			addMacroFunction("def", ((expressions, context) -> {
				if(expressions.size() < 2) {
					throw new RuntimeException("function def requires at least two arguments, found: " + expressions.size());
				}

				if(expressions.size() % 2 == 1) {
					throw new RuntimeException("function def requires an even number of arguments, pairs of key values. Found: " + expressions.size());
				}


				int arity = 1;

				String id = null;
				Object value = null;

				Context parent = context.getParent();

				while(!expressions.isEmpty()) {

					Object uncheckedId = expressions.remove(0);


					if(!(uncheckedId instanceof Identifier)) {
						throw new RuntimeException("function def expected to find Identifier at arity " + arity + " but found: " + uncheckedId.getClass());
					}


					arity++;

					id = ((Identifier) uncheckedId).name;

					value = expressions.remove(0).eval(context);
					arity++;

					parent.putRef(id, value);
				}


				return null;
			}));

			addMacroFunction("defn", ((expressions, context) -> {
				if(expressions.size() < 2) {
					throw new RuntimeException("function defn requires at least two arguments, found: " + expressions.size());
				}

				Context parent = context.getParent();



				Expression body = expressions.remove(expressions.size() - 1);

				List<String> ids = expressions.stream().map(ex -> {
					if(ex instanceof Identifier) {
						return ((Identifier) ex).name;
					} else{
						throw new RuntimeException("function fn expects all arguments except the last one to be Identifiers, found: " + ex.getClass());
					}
				}).collect(Collectors.toList());

				String name = ids.remove(0);

				LispFunction result = (args, fnContext) -> {
					Context inner = Context.createContext(context, false);

					for(int i = 0; i < args.size() && i < ids.size(); i++) {
						inner.putRef(ids.get(i), args.get(i));
					}

					return body.eval(inner);
				};

				parent.putRef(name, result);

				return null;
			}));

			addMacroFunction("import", ((expressions, context) -> {

				if(expressions.size() < 2) {
					throw new RuntimeException("function import requires at least two arguments, a series of Strings of packages to import and one SExpression to execute with the imports");
				}

				Expression body = expressions.remove(expressions.size() - 1);


				List<Context> imports = expressions.stream()
						.map(ex -> ex.eval(context))
						.map(String.class::cast)
						.map(i -> mainLisp.importPackage(i))
						.collect(Collectors.toList());


				Context importContext = Context.createContext(context, imports);

				return body.eval(importContext);
			}));

			addFunction("head", (args, context) -> {
				if(args.size() != 1)
					throw new IllegalArgumentException("function first requires one argument, found: " + args.size());
				else {
					@SuppressWarnings("unchecked")
					List<Object> first = (List<Object>) args.get(0);

					return first.get(0);
				}

			});

			addFunction("tail", (args, context) -> {
				if(args.size() != 1)
					throw new IllegalArgumentException("function first requires one argument, found: " + args.size());
				else {
					@SuppressWarnings("unchecked")
					List<Object> first = (List<Object>) args.get(0);
					first.remove(0);
					return first;
				}

			});

			addFunction("print", (args, context) -> {
				log.info("Print: {}", args.stream().map(String::valueOf).collect(Collectors.joining()));

				if(args.size() == 1) {
					return args.get(0);
				} else {
					return args;
				}
			});

			addFunction("typeof", (args, context) -> args.stream().map(Object::getClass).map(Object::toString).collect(Collectors.toList()));

			addFunction("=", (args, context) -> {
				List<Object> checked = new ArrayList<>(args.size());

				for(Object obj : args) {
					Object first = prepareForEquals(obj);

					for(Object second : checked) {

						if(!Objects.equals(first, second))
							return false;
					}

					checked.add(first);
				}

				return true;
			});

			addFunction("+", (args, context) -> {
				if(args.isEmpty())
					throw new IllegalArgumentException("function + requires at least one argument: ");
				else {
					Object first = args.remove(0);

					if(first instanceof String) {
						StringBuilder builder = new StringBuilder(first.toString());

						args.forEach(builder::append);

						return builder.toString();
					} else {
						BigDecimal result = (BigDecimal) first;
						return args.stream().map(BigDecimal.class::cast).reduce(result, BigDecimal::add);
					}
				}

			});

			addFunction("-", (args, context) -> {
				if(args.isEmpty())
					throw new IllegalArgumentException("function - requires at least one argument: ");
				else {
					return args.stream().map(BigDecimal.class::cast).reduce(BigDecimal::subtract).get();
				}

			});

			addFunction("*", (args, context) -> {
				if(args.isEmpty())
					throw new IllegalArgumentException("function * requires at least one argument: ");
				else {
					return args.stream().map(BigDecimal.class::cast).reduce(BigDecimal::multiply).get();
				}

			});

			addFunction("/", (args, context) -> {
				if(args.isEmpty())
					throw new IllegalArgumentException("function / requires at least one argument: ");
				else {
					return args.stream().map(BigDecimal.class::cast).reduce(BigDecimal::divide).get();
				}

			});
		}

		public boolean containsRef(String key) {
			return map.containsKey(key);
		}

		public Object getRef(String key) {
			if(map.containsKey(key))
				return map.get(key);
			else
				throw new RuntimeException("Undefined ref: " + key);
		}

		public void putRef(String key, Object value) {
			map.put(key, value);
		}

		@Override
		public Context getParent() {
			return this;
		}
	}


	interface Expression {

		Object eval(Context context);

		String display();

	}

	private static class SExpression implements Expression {

		List<Expression> expressions;

		SExpression(List<Expression> expressions) {
			this.expressions = expressions;
		}

		public Object eval(Context context) {

			Object head = expressions.remove(0).eval(context);

			if(head instanceof LispMacroFunction) {
				//Macros eval their own arguments.
				@SuppressWarnings("unchecked")
				LispMacroFunction func = (LispMacroFunction) head;

				return func.apply(expressions, context);
			} else if(head instanceof LispFunction) {
				//Eval all arguments for the function.
				List<Object> values = expressions.stream().map(e -> e.eval(context)).collect(Collectors.toList());

				@SuppressWarnings("unchecked")
				LispFunction func = (LispFunction) head;
				return func.apply(values, context);
			} else {
				List<Object> values = expressions.stream().map(e -> e.eval(context)).collect(Collectors.toList());
				values.add(0, head); //Add the head back onto the front of the list.
				return values;
			}

		}

		@Override
		public String display() {
			return expressions.stream().map(Expression::display).collect(Collectors.joining(" ", "(", ")"));
		}

		@Override
		public String toString() {
			return "{\"className\": \"" + SExpression.class + "\"" +
					",\"expressions\": " + expressions.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]")) +
					'}';
		}
	}

	private static class Literal implements Expression {

		Object value;

		Literal(Object value) {
			this.value = value;
		}

		public Object eval(Context context) {
			return value;
		}

		@Override
		public String display() {
			return value instanceof String ? "\"" + value + "\"" : String.valueOf(value);
		}

		@Override
		public String toString() {
			return "{\"className\": \"" + Literal.class + "\"" +
					",\"value\": \"" + value + "\"" +
					'}';
		}
	}

	private static class Identifier implements Expression {

		String name;

		Identifier(String name) {
			this.name = name;
		}

		public Object eval(Context context) {
			if(context.containsRef(name))
				return context.getRef(name);
			else
				throw new RuntimeException("No identifier named " + name + " in scope");
		}

		@Override
		public String display() {
			return name;
		}

		@Override
		public String toString() {
			return "{\"className\": \"" + Identifier.class + "\"" +
					",\"name\": \"" + name + '\'' + "\"" +
					'}';
		}
	}

	private Expression categorize(String value) {
		if(value.matches("^\\d\\.?\\d*")) {
			return new Literal(new BigDecimal(value));
		} else if (value.startsWith("\"")) {
			return new Literal(value.substring(1, value.length() - 1));
		} else {
			return new Identifier(value);
		}
	}


	private Expression parens(Queue<String> tokens, List<Expression> expressions) {
		if(tokens.isEmpty()) {
			return new SExpression(expressions);
		}

		String next = tokens.poll();

		switch (next) {
			case "(":
				expressions.add(parens(tokens, new ArrayList<>()));
				return parens(tokens, expressions);
			case ")":
				return new SExpression(expressions);
			default:
				expressions.add(categorize(next));
				return parens(tokens, expressions);
		}
	}


	private Deque<String> tokenize(String fileName) throws IOException {
		File file = Paths.get(fileName).toFile();

		try (Reader reader = new BufferedReader(new FileReader(file))) {

			Deque<String> tokens = new ArrayDeque<>();

			int iNext;


			while((iNext = reader.read()) != -1) {
				char next = (char) iNext;

				if(Character.isWhitespace(next))
					continue;

				switch (next) {
					case '(':
						tokens.add("(");
						break;
					case ')':
						tokens.add(")");
						break;

					case '"': {
						StringBuilder builder = new StringBuilder("\"");

						do {
							next = (char) reader.read();
							builder.append(next);

						} while (next != '"');

						tokens.add(builder.toString());
						break;
					}
					default: {
						StringBuilder builder = new StringBuilder();

						do {
							builder.append(next);
							next = (char) reader.read();
						} while (next != '(' && next != ')' && !Character.isWhitespace(next));

						tokens.add(builder.toString());

						if(next == ')')
							tokens.add(")");

						if(next == '(')
							tokens.add("(");

						break;
					}

				}
			}

			return tokens;
		}
	}





}
