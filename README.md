# PDDL Playground for Pepper

This is a follow-up of
the project [PDDL Playground](https://github.com/aldebaran/pddl-playground) for Android.
It uses the [PDDL Planning library](https://github.com/aldebaran/pddl-planning-android) and
a planner (such as [Fast Downward for Android](https://github.com/aldebaran/fast-downward-android))
to produce robotic behaviors on Pepper.

## Theory

Whenever the state changes, the planner is run and a plan is made, containing a sequence of actions.
These actions are implemented using Qi SDK actions,
so that they interface with the robot actuators,
which then changes the environment in some way.
These actions can also be used to directly update the state.
Regardless of the actions, the world also changes on its own,
so the sensors on the robot captures data and this is in turn processed by the extractors,
which convert it into meaningful states for the planner, and update these states.
And the loop continues.

Developing this behavior required us to:
- Define a domain with predicates, and object types, and thus determine all possible states.
- Express the goals in terms of states.
- Define the effects of all the possible actions,
  i.e. the possible state changes they are meant to lead to.
- Implement extractors, which are autonomous components dedicated to
  keep the state up-to-date with what the robot actually experiences.
- Run the planner and execute the output plan.
- Rerun the planner for every unexpected state change.

You should be able to run this app out of the box, and get Pepper to greet you and joke with you.
You can tweak goals in the code by calling `Controller.setGoal`.

## Getting Started

This is an Android Studio project.
It should build out of the box.
If it requires a fix to do so, please contribute back.

To run it, you will have to provide a `PlanSearchFunction`.
By default, it is configured to use another Android service,
[Fast Downward for Android](https://github.com/aldebaran/fast-downward-android).
You can install it on Pepper's tablet before running this application.

When running and in the foreground, Pepper should engage with you and tell you a random joke.
